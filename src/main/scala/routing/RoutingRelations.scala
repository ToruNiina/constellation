package constellation.routing

import scala.math.pow
import scala.collection.mutable.HashMap
import freechips.rocketchip.config.{Parameters}

/** Routing and channel allocation policy
 *
 * @param f function that takes in a nodeId, source channel, next channel, and packet routing info.
 *          Returns True if packet can acquire/proceed to this next channel, False if not. An example
 *          is the bidirectionalLine method; see its docstring.
 * @param isEscape function that takes in ChannelRoutingInfo and a virtual network ID. Returns True if the
 *                 channel represented by ChannelRoutingInfo is an escape channel and False if it is not.
 *                 By default, we ignore the escape-channel-based deadlock-free properties, and just check
 *                 for deadlock-freedom by verifying lack of a cyclic dependency.
 */
abstract class RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo): Boolean

  val memoize = new HashMap[(Int, ChannelRoutingInfo, ChannelRoutingInfo, FlowRoutingInfo), Boolean]()
  def apply(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo): Boolean = {
    require(nodeId == srcC.dst && nodeId == nxtC.src)
    val key = (nodeId, srcC, nxtC, flow)
    memoize.getOrElseUpdate(key, rel(nodeId, srcC, nxtC, flow))
  }

  def isEscape(c: ChannelRoutingInfo, vNetId: Int): Boolean = true

}

/** Given a deadlock-prone routing relation and a routing relation representing the network's escape
  *  channels, returns a routing relation that adds the escape channels to the deadlock-prone relation.
  *
  *  @param escapeRouter routing relation representing the network's escape channels
  *  @param normalrouter deadlock-prone routing relation
  *  @param nEscapeChannels number of escape channels
  */
class EscapeChannelRouting(
  escapeRouter: RoutingRelation, normalRouter: RoutingRelation, nEscapeChannels: Int = 1
) extends RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    if (srcC.src == -1) {
      if (nxtC.vc >= nEscapeChannels) { // if ingress and jumping into normal channel, use normal relation
        normalRouter(nodeId, srcC, nxtC.copy(vc=nxtC.vc-nEscapeChannels, n_vc=nxtC.n_vc-nEscapeChannels), flow)
      } else { // else use ingress and jump into escape channel
        escapeRouter(nodeId, srcC, nxtC.copy(n_vc=nEscapeChannels), flow)
      }
    } else if (nxtC.vc < nEscapeChannels) {
      escapeRouter(nodeId, srcC, nxtC.copy(n_vc=nEscapeChannels), flow)
    } else if (srcC.vc >= nEscapeChannels && nxtC.vc >= nEscapeChannels) {
      normalRouter(nodeId,
        srcC.copy(vc=srcC.vc-nEscapeChannels, n_vc=srcC.n_vc-nEscapeChannels),
        nxtC.copy(vc=nxtC.vc-nEscapeChannels, n_vc=nxtC.n_vc-nEscapeChannels),
        flow)
    } else {
      false
    }
  }
  override def isEscape(c: ChannelRoutingInfo, v: Int) = {
    c.isIngress || c.isEgress || c.vc < nEscapeChannels
  }
}

/**
  * Allows all routing. Probably not very useful
  */
class AllLegalRouting extends RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = true
}

/** An example of the parameter `f` for the RoutingRelation class that routes traffic along a
  * straight-line network in the direction of the packet's destination. Returns true if the selected
  * next channel moves the packet in the direction of the destination node and false if it does not.
  *
  * @param nodeId ID of the node the packet is currently at
  * @param srcC the source channel that brought the packet to nodeId
  * @param nxtC the channel proposed for the packet's next hop along the network
  * @param flow the packet's routing info, including its destination node
  */
class BidirectionalLineRouting extends RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    if (nodeId < nxtC.dst) flow.dst >= nxtC.dst else flow.dst <= nxtC.dst
  }
}

class UnidirectionalTorus1DDatelineRouting(nNodes: Int) extends RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    if (srcC.src == -1)  {
      nxtC.vc == nxtC.n_vc - 1
    } else if (srcC.vc == 0) {
      nxtC.vc == 0
    } else if (nodeId == nNodes - 1) {
      nxtC.vc < srcC.vc
    } else {
      nxtC.vc <= srcC.vc && (nxtC.vc != 0 || flow.dst > nodeId)
    }
  }
}

class BidirectionalTorus1DDatelineRouting(nNodes: Int) extends RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    if (srcC.src == -1)  {
      nxtC.vc == nxtC.n_vc - 1
    } else if (srcC.vc == 0) {
      nxtC.vc == 0
    } else if ((nxtC.dst + nNodes - nodeId) % nNodes == 1) {
      if (nodeId == nNodes - 1) {
        nxtC.vc < srcC.vc
      } else {
        nxtC.vc <= srcC.vc && (nxtC.vc != 0 || flow.dst > nodeId)
      }
    } else if ((nodeId + nNodes - nxtC.dst) % nNodes == 1) {
      if (nodeId == 0) {
        nxtC.vc < srcC.vc
      } else {
        nxtC.vc <= srcC.vc && (nxtC.vc != 0 || flow.dst < nodeId)
      }
    } else {
      false
    }
  }
}

class BidirectionalTorus1DShortestRouting(nNodes: Int) extends RoutingRelation {
  val base = new BidirectionalTorus1DDatelineRouting(nNodes)
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    val cwDist = (flow.dst + nNodes - nodeId) % nNodes
    val ccwDist = (nodeId + nNodes - flow.dst) % nNodes
    val distSel = if (cwDist < ccwDist) {
      (nxtC.dst + nNodes - nodeId) % nNodes == 1
    } else if (cwDist > ccwDist) {
      (nodeId + nNodes - nxtC.dst) % nNodes == 1
    } else {
      true
    }
    distSel && base(nodeId, srcC, nxtC, flow)
  }
}

class BidirectionalTorus1DRandomRouting(nNodes: Int) extends RoutingRelation {
  val base = new BidirectionalTorus1DDatelineRouting(nNodes)
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    val sel = if (srcC.src == -1) {
      true
    } else if ((nodeId + nNodes - srcC.src) % nNodes == 1) {
      (nxtC.dst + nNodes - nodeId) % nNodes == 1
    } else {
      (nodeId + nNodes - nxtC.dst) % nNodes == 1
    }
    sel && base(nodeId, srcC, nxtC, flow)
  }
}

class ButterflyRouting(kAry: Int, nFly: Int) extends RoutingRelation {
  require(kAry >= 2 && nFly >= 2)
  val height = pow(kAry, nFly-1).toInt
  def digitsToNum(dig: Seq[Int]) = dig.zipWithIndex.map { case (d,i) => d * pow(kAry,i).toInt }.sum
  val table = (0 until pow(kAry, nFly).toInt).map { i =>
    (0 until nFly).map { n => (i / pow(kAry, n).toInt) % kAry }
  }
  val channels = (1 until nFly).map { i =>
    table.map { e => (digitsToNum(e.drop(1)), digitsToNum(e.updated(i, e(0)).drop(1))) }
  }

  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    val (nxtX, nxtY) = (nxtC.dst / height, nxtC.dst % height)
    val (nodeX, nodeY) = (nodeId / height, nodeId % height)
    val (dstX, dstY) = (flow.dst / height, flow.dst % height)
    if (dstX <= nodeX) {
      false
    } else if (nodeX == nFly-1) {
      true
    } else {
      val dsts = (nxtX until nFly-1).foldRight((0 until height).map { i => Seq(i) }) {
        case (i,l) => (0 until height).map { s => channels(i).filter(_._1 == s).map { case (_,d) =>
          l(d)
        }.flatten }
      }
      dsts(nxtY).contains(flow.dst % height)
    }
  }
}

class BidirectionalTreeRouting(dAry: Int) extends RoutingRelation {
  /** Returns a boolean indicating whether src is an ancestor node of dst */
  def isAncestor(src: Int, dst: Int): Boolean = {
    if (src == dst) {
      true
    } else if (src > dst) {
      false
    } else {
      ((dAry * src + 1) to (dAry * src + dAry)).foldLeft(false)((sofar, nextChild) => sofar || isAncestor(nextChild, dst))
    }
  }
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    if (isAncestor(nodeId, flow.dst)) {
      isAncestor(nxtC.dst, flow.dst) && (nxtC.dst >= nodeId)
    } else {
      isAncestor(nxtC.dst, nodeId)
    }
  }
}

class Mesh2DDimensionOrderedRouting(nX: Int, nY: Int, firstDim: Int = 0) extends RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    val (nxtX, nxtY)   = (nxtC.dst % nX , nxtC.dst / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (flow.dst % nX , flow.dst / nX)

    if (firstDim == 0) {
      if (dstX != nodeX) {
        (if (nodeX < nxtX) dstX >= nxtX else dstX <= nxtX) && nxtY == nodeY
      } else {
        (if (nodeY < nxtY) dstY >= nxtY else dstY <= nxtY) && nxtX == nodeX
      }
    } else {
      if (dstY != nodeY) {
        (if (nodeY < nxtY) dstY >= nxtY else dstY <= nxtY) && nxtX == nodeX
      } else {
        (if (nodeX < nxtX) dstX >= nxtX else dstX <= nxtX) && nxtY == nodeY
      }
    }
  }
}

// WARNING: Not deadlock free
class Mesh2DMinimalRouting(nX: Int, nY: Int) extends RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    val (nxtX, nxtY)   = (nxtC.dst % nX , nxtC.dst / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (flow.dst % nX, flow.dst / nX)

    val xR = (if (nodeX < nxtX) dstX >= nxtX else if (nodeX > nxtX) dstX <= nxtX else nodeX == nxtX)
    val yR = (if (nodeY < nxtY) dstY >= nxtY else if (nodeY > nxtY) dstY <= nxtY else nodeY == nxtY)
    xR && yR
  }
}

class Mesh2DWestFirstRouting(nX: Int, nY: Int) extends RoutingRelation {
  val base = new Mesh2DMinimalRouting(nX, nY)
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    val (nxtX, nxtY)   = (nxtC.dst % nX , nxtC.dst / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (flow.dst % nX , flow.dst / nX)

    if (dstX < nodeX) {
      nxtX == nodeX - 1
    } else {
      base(nodeId, srcC, nxtC, flow)
    }
  }
}

class Mesh2DNorthLastRouting(nX: Int, nY: Int) extends RoutingRelation {
  val base = new Mesh2DMinimalRouting(nX, nY)
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    val (nxtX, nxtY)   = (nxtC.dst % nX , nxtC.dst / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (flow.dst % nX , flow.dst / nX)

    val minimal = base(nodeId, srcC, nxtC, flow)
    if (dstY > nodeY && dstX != nodeX) {
      minimal && nxtY != nodeY + 1
    } else if (dstY > nodeY) {
      nxtY == nodeY + 1
    } else {
      minimal
    }
  }
}

class Mesh2DEscapeRouting(nX: Int, nY: Int) extends EscapeChannelRouting(
  new Mesh2DDimensionOrderedRouting(nX, nY), new Mesh2DMinimalRouting(nX, nY))

class UnidirectionalTorus2DDatelineRouting(nX: Int, nY: Int) extends RoutingRelation {
  val baseX = new UnidirectionalTorus1DDatelineRouting(nX)
  val baseY = new UnidirectionalTorus1DDatelineRouting(nY)
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {

    val (nxtX, nxtY)   = (nxtC.dst % nX , nxtC.dst / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (flow.dst % nX , flow.dst / nX)
    val (srcX, srcY)   = (srcC.src % nX , srcC.src / nX)

    val turn = nxtX != srcX && nxtY != srcY
    if (srcC.src == -1 || turn) {
      nxtC.vc == nxtC.n_vc - 1
    } else if (srcX == nxtX) {
      baseY(
        nodeY,
        srcC.copy(src=srcY, dst=nodeY),
        nxtC.copy(src=nodeY, dst=nxtY),
        flow.copy(dst=dstY)
      )
    } else if (srcY == nxtY) {
      baseX(
        nodeX,
        srcC.copy(src=srcX, dst=nodeX),
        nxtC.copy(src=nodeX, dst=nxtX),
        flow.copy(dst=dstX)
      )
    } else {
      false
    }
  }
}

class BidirectionalTorus2DDatelineRouting(nX: Int, nY: Int) extends RoutingRelation {
  val baseX = new UnidirectionalTorus1DDatelineRouting(nX)
  val baseY = new UnidirectionalTorus1DDatelineRouting(nY)
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    val (nxtX, nxtY)   = (nxtC.dst % nX , nxtC.dst / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (flow.dst % nX , flow.dst / nX)
    val (srcX, srcY)   = (srcC.src % nX , srcC.src / nX)

    if (srcC.src == -1) {
      nxtC.vc == nxtC.n_vc - 1
    } else if (nodeX == nxtX) {
      baseY(
        nodeY,
        srcC.copy(src=srcY, dst=nodeY),
        nxtC.copy(src=nodeY, dst=nxtY),
        flow.copy(dst=dstY)
      )
    } else if (nodeY == nxtY) {
      baseX(
        nodeX,
        srcC.copy(src=srcX, dst=nodeX),
        nxtC.copy(src=nodeX, dst=nxtX),
        flow.copy(dst=dstX)
      )
    } else {
      false
    }
  }
}

class DimensionOrderedUnidirectionalTorus2DDatelineRouting(nX: Int, nY: Int) extends RoutingRelation {
  val base = new UnidirectionalTorus2DDatelineRouting(nX, nY)
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    val (nxtX, nxtY)   = (nxtC.dst % nX , nxtC.dst / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (flow.dst % nX , flow.dst / nX)
    val (srcX, srcY)   = (srcC.src % nX , srcC.src / nX)

    def sel = if (dstX != nodeX) {
      nxtY == nodeY
    } else {
      nxtX == nodeX
    }
    base(nodeId, srcC, nxtC, flow) && sel
  }
}

class DimensionOrderedBidirectionalTorus2DDatelineRouting(nX: Int, nY: Int) extends RoutingRelation {
  val baseX = new BidirectionalTorus1DShortestRouting(nX)
  val baseY = new BidirectionalTorus1DShortestRouting(nY)
  val base = new BidirectionalTorus2DDatelineRouting(nX, nY)
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {

    val (nxtX, nxtY)   = (nxtC.dst % nX , nxtC.dst / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (flow.dst % nX , flow.dst / nX)
    val (srcX, srcY)   = (srcC.src % nX , srcC.src / nX)

    val xdir = baseX(
      nodeX,
      srcC.copy(src=(if (srcC.src == -1) -1 else srcX), dst=nodeX),
      nxtC.copy(src=nodeX, dst=nxtX),
      flow.copy(dst=dstX)
    )
    val ydir = baseY(
      nodeY,
      srcC.copy(src=(if (srcC.src == -1) -1 else srcY), dst=nodeY),
      nxtC.copy(src=nodeY, dst=nxtY),
      flow.copy(dst=dstY)
    )

    val sel = if (dstX != nodeX) xdir else ydir

    sel && base(nodeId, srcC, nxtC, flow)
  }
}

class TerminalPlaneRouting(f: RoutingRelation, nNodes: Int) extends RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    if (srcC.isIngress && nxtC.dst < nNodes) {
      true
    } else if (nxtC.dst == flow.dst) {
      true
    } else if (nodeId < nNodes && nxtC.dst < nNodes && nodeId != flow.dst - 2 * nNodes) {
      if (srcC.src >= nNodes) {
        f(nodeId,
          srcC.copy(src=(-1), vc=0, n_vc=1),
          nxtC,
          flow.copy(dst=flow.dst-2*nNodes))
      } else {
        f(nodeId,
          srcC,
          nxtC,
          flow.copy(dst=flow.dst-2*nNodes))
      }
    } else {
      false
    }
  }
  override def isEscape(c: ChannelRoutingInfo, v: Int) = {
    c.src >= nNodes || c.dst >= nNodes || f.isEscape(c, v)
  }
}


// The below tables implement support for virtual subnetworks in a variety of ways
// NOTE: The topology must have sufficient virtual channels for these to work correctly
// TODO: Write assertions to check this

/** Produces a system with support for n virtual subnetworks. The output routing relation ensures that
  * the virtual subnetworks do not block each other. This is accomplished by allocating a portion of the
  * virtual channels to each subnetwork; all physical resources are shared between virtual subnetworks
  * but virtual channels and buffers are not shared.
  */
class NonblockingVirtualSubnetworksRouting(f: RoutingRelation, n: Int) extends RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    if (srcC.isIngress) {
      (nxtC.vc % n == flow.vNet) && f(
        nodeId,
        srcC,
        nxtC.copy(vc=nxtC.vc / n, n_vc=nxtC.n_vc / n),
        flow.copy(vNet=0)
      )
    } else {
      // only allow a virtual subnet to use virtual channel is channelID % numnetworks = virtual network ID
      (nxtC.vc % n == flow.vNet) && f(
        nodeId,
        srcC.copy(vc=srcC.vc / n, n_vc=srcC.n_vc / n),
        nxtC.copy(vc=nxtC.vc / n, n_vc=nxtC.n_vc / n),
        flow.copy(vNet=0)
      )
    }
  }
  override def isEscape(c: ChannelRoutingInfo, v: Int) = {
    f.isEscape(c.copy(vc=c.vc / n, n_vc=c.n_vc / n), 0)
  }
}

// Virtual subnets with 1 dedicated virtual channel each, and some number of shared channels
class SharedNonblockingVirtualSubnetworksRouting(f: RoutingRelation, n: Int, nSharedChannels: Int) extends RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    def trueVIdToVirtualVId(vId: Int) = if (vId < n) 0 else vId - n
    if (nxtC.vc < n) {
      nxtC.vc == flow.vNet && f(
        nodeId,
        srcC.copy(vc=trueVIdToVirtualVId(srcC.vc), n_vc = 1 + nSharedChannels),
        nxtC.copy(vc=0, n_vc = 1 + nSharedChannels),
        flow.copy(vNet=0)
      )
    } else {
      f(nodeId,
        srcC.copy(vc=trueVIdToVirtualVId(srcC.vc), n_vc = 1 + nSharedChannels),
        nxtC.copy(vc=trueVIdToVirtualVId(nxtC.vc), n_vc = 1 + nSharedChannels),
        flow.copy(vNet=0)
      )
    }
  }
  override def isEscape(c: ChannelRoutingInfo, v: Int) = {
    def trueVIdToVirtualVId(vId: Int) = if (vId < n) 0 else vId - n
    f.isEscape(c.copy(vc=trueVIdToVirtualVId(c.vc), n_vc = 1 + nSharedChannels), 0)
  }
}

class BlockingVirtualSubnetworksRouting(f: RoutingRelation, n: Int, nDedicated: Int = 1) extends RoutingRelation {
  def rel(nodeId: Int, srcC: ChannelRoutingInfo, nxtC: ChannelRoutingInfo, flow: FlowRoutingInfo) = {
    val lNxtV = nxtC.vc - flow.vNet * nDedicated
    val lSrcV = srcC.vc - flow.vNet * nDedicated
    if (srcC.isIngress && lNxtV >= 0) {
      f(nodeId,
        srcC,
        nxtC.copy(n_vc = nxtC.n_vc - flow.vNet * nDedicated, vc = lNxtV),
        flow.copy(vNet=0)
      )
    } else if (lNxtV < 0 || lSrcV < 0) {
      false
    } else {
      f(nodeId,
        srcC.copy(n_vc = srcC.n_vc - flow.vNet * nDedicated, vc = lSrcV),
        nxtC.copy(n_vc = nxtC.n_vc - flow.vNet * nDedicated, vc = lNxtV),
        flow.copy(vNet=0)
      )
    }
  }
  override def isEscape(c: ChannelRoutingInfo, v: Int) = {
    c.vc >= v && f.isEscape(c.copy(vc=c.vc - v * nDedicated, n_vc=c.n_vc - v * nDedicated), 0)
  }
}
