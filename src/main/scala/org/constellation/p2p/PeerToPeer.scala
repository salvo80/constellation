package org.constellation.p2p

import java.security.{KeyPair, PublicKey}
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Terminated}
import akka.pattern.pipe
import akka.util.Timeout
import org.constellation.consensus.Consensus.{ConsensusRoundState, PeerMemPoolUpdated, PeerProposedBlock}
import org.constellation.p2p.PeerToPeer._
import org.constellation.state.ChainStateManager.GetCurrentChainState

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.Duration

object PeerToPeer {

  case class AddPeer(address: String)

  case class PeerRef(actorRef: ActorRef)

  case class Peers(peers: Seq[String])

  case class Id(id: PublicKey)

  case class GetPeers()

  case class GetPeerActorRefs()

  case class GetId()

  case class GetBalance(account: PublicKey)

  case class HandShake()
}

class PeerToPeer(publicKey: PublicKey, system: ActorSystem, consensusActor: ActorRef)
                (implicit timeout: Timeout) extends Actor with ActorLogging {

  implicit val executionContext: ExecutionContextExecutor = context.system.dispatcher

  var peers: Set[ActorRef] = Set.empty[ActorRef]

  def broadcast(message: Any ): Unit = {
    peers.foreach {
      peer => peer ! message
    }
  }

  def broadcastAsk(message: Any): Seq[Future[Any]] = {
    import akka.pattern.ask
    peers.map{p => p ? message}.toSeq
  }

  override def receive: Receive = {

    case AddPeer(peerAddress) =>
      log.debug(s"Received a request to add peer $peerAddress")

      /*
        adds peer to actor system, res is a future of actor ref,
        sends the actor ref back to this actor, handshake occurs below
      */
      context.actorSelection(peerAddress).resolveOne()(timeout).map( PeerRef(_) ).pipeTo(self)

    case PeerRef(newPeerRef: ActorRef) =>

      if (!peers.contains(newPeerRef) && newPeerRef != self){
        context.watch(newPeerRef)
        log.debug(s"Watching $newPeerRef}")

        //Introduce ourselves
        newPeerRef ! HandShake
        log.debug(s"HandShake $newPeerRef}")

        //Ask for its friends
        newPeerRef ! GetPeers
        log.debug(s"GetPeers $newPeerRef}")

        //Tell our existing peers
        broadcast(AddPeer(newPeerRef.path.toSerializationFormat))

        //Add to the current list of peers
        peers += newPeerRef

      } else log.debug("We already know this peer, discarding")

    case Peers(peersI) => peersI.foreach( self ! AddPeer(_))

    case HandShake =>
      log.debug(s"Received a handshake from ${sender().path.toStringWithoutAddress}")
      peers += sender()

    case GetPeers => sender() ! Peers(peers.toSeq.map(_.path.toSerializationFormat))

    case GetPeerActorRefs => sender() ! peers

    case Terminated(actorRef) =>
      log.debug(s"Peer ${actorRef} has terminated. Removing it from the list.")
      peers -= actorRef

    case GetId =>
      sender() ! Id(publicKey)

    case PeerMemPoolUpdated(transactions, peer, round) =>
      consensusActor ! PeerMemPoolUpdated(transactions, peer, round)

    case PeerProposedBlock(block, peer) =>
      consensusActor ! PeerProposedBlock(block, peer)

  }

}