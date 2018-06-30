package org.constellation.primitives

import org.constellation.LevelDB
import org.constellation.consensus.Consensus.{CC, RoundHash}
import org.constellation.primitives.Schema._
import org.constellation.util.Signed

import scala.collection.concurrent.TrieMap
import constellation._

trait BundleDataExt extends Reputation with MetricsExt with TransactionExt {

  @volatile var db: LevelDB

  var genesisBundle : Bundle = _
  def genesisTXHash: String = genesisBundle.extractTX.head.hash

  @volatile var last100ValidBundleMetaData : Seq[BundleMetaData] = Seq()
  def lastValidBundle: Bundle = last100ValidBundleMetaData.last.bundle
  def lastValidBundleHash = ParentBundleHash(lastValidBundle.hash)

  @volatile var maxBundleMetaData: BundleMetaData = _
  def maxBundle: Bundle = maxBundleMetaData.bundle

  @volatile var syncPendingBundleHashes: Set[String] = Set()
  @volatile var syncPendingTXHashes: Set[String] = Set()

  @volatile var activeDAGBundles: Seq[BundleMetaData] = Seq()

  @volatile var linearCheckpointBundles: Set[Bundle] = Set[Bundle]()
  @volatile var lastCheckpointBundle: Option[Bundle] = None
  val checkpointsInProgress: TrieMap[RoundHash[_ <: CC], Boolean] = TrieMap()

  implicit class BundleExtData(b: Bundle) {

    def meta: Option[BundleMetaData] = db.getAs[BundleMetaData](b.hash)

    def scoreFrom(m: BundleMetaData): Double = {
      val norm = normalizeReputations(m.reputations)
      val repScore = b.extractIds.toSeq.map { id => norm.getOrElse(id.b58, 0.1)}.sum
      b.maxStackDepth * 500 + extractTXHash.size + repScore * 10
    }

    def score: Option[Double] = meta.flatMap{ m =>
      if (m.reputations.isEmpty) None
      else Some(scoreFrom(m))
    }

    def totalScore : Option[Double] = meta.flatMap{_.totalScore}

    def minTime: Long = meta.get.rxTime
    def pretty: String = s"hash: ${b.short}, depth: ${b.maxStackDepth}, numTX: ${extractTXHash.size}, " +
      s"numId: ${b.extractIds.size}, " +
      s"score: $score, totalScore: $totalScore, height: ${meta.map{_.height}}, " +
      s"parent: ${meta.map{_.bundle.extractParentBundleHash.pbHash.slice(0, 5)}} firstId: ${b.extractIds.head.short}"

    def extractTXHash: Set[TransactionHash] = {
      def process(s: Signed[BundleData]): Set[TransactionHash] = {
        val bd = s.data.bundles
        val depths = bd.map {
          case b2: Bundle =>
            process(b2.bundleData)
          case h: TransactionHash => Set(h)
          case _ => Set[TransactionHash]()
        }
        if (depths.nonEmpty) {
          depths.reduce( (s1: Set[TransactionHash], s2: Set[TransactionHash]) => s1 ++ s2)
        } else {
          Set[TransactionHash]()
        }
      }
      process(b.bundleData)
    }

    def extractTX: Set[TX] = extractTXHash.flatMap{ z => db.getAs[TX](z.txHash)}

    def reputationUpdate: Map[String, Long] = {
      b.extractIds.map{_.b58 -> 1L}.toMap
    }

  }

  def jaccard[T](t1: Set[T], t2: Set[T]): Double = {
    t1.intersect(t2).size.toDouble / t1.union(t2).size.toDouble
  }

  def prettifyBundle(b: Bundle): String = b.pretty

  def findAncestors(
                     parentHash: String,
                     ancestors: Seq[BundleMetaData] = Seq()
                   ): Seq[BundleMetaData] = {
    val parent = db.getAs[BundleMetaData](parentHash)
    if (parent.isEmpty) {
      if (parentHash != "coinbase") {
        syncPendingBundleHashes += parentHash
      }
      ancestors
    } else if (parentHash == genesisBundle.hash) {
      Seq(db.getAs[BundleMetaData](genesisBundle.hash).get) ++ ancestors
    } else {
      findAncestors(
        parent.get.bundle.extractParentBundleHash.pbHash,
        Seq(parent.get) ++ ancestors
      )
    }
  }

  def findAncestorsUpToLastResolved(
                                     parentHash: String,
                                     ancestors: Seq[BundleMetaData] = Seq()
                                   ): Seq[BundleMetaData] = {
    val parent = db.getAs[BundleMetaData](parentHash)
    def updatedAncestors = Seq(parent.get) ++ ancestors
    if (parent.isEmpty) {
      if (parentHash != "coinbase") {
        syncPendingBundleHashes += parentHash
      }
      ancestors
    } else if (parent.get.isResolved) updatedAncestors
    else {
      findAncestors(
        parent.get.bundle.extractParentBundleHash.pbHash,
        updatedAncestors
      )
    }
  }

  def findAncestorsUpTo(
                         parentHash: String,
                         ancestors: Seq[BundleMetaData] = Seq(),
                         upTo: Int = 1
                       ): Seq[BundleMetaData] = {
    val parent = db.getAs[BundleMetaData](parentHash)
    def updatedAncestors = Seq(parent.get) ++ ancestors
    if (parent.isEmpty || updatedAncestors.size >= upTo) {
      ancestors
    }
    else {
      findAncestors(
        parent.get.bundle.extractParentBundleHash.pbHash,
        updatedAncestors
      )
    }
  }

  val confirmWindow = 5
  @volatile var txInMaxBundleNotInValidation: Set[String] = Set()


  def updateMaxBundle(bundleMetaData: BundleMetaData): Unit = {

    val genCheck = totalNumValidatedTX == 1 || bundleMetaData.bundle.maxStackDepth >= 2

    if (bundleMetaData.totalScore.get > maxBundle.totalScore.get && genCheck) {
      maxBundle.synchronized {
        maxBundleMetaData = bundleMetaData
        val ancestors = findAncestorsUpTo(
          bundleMetaData.bundle.extractParentBundleHash.pbHash,
          upTo = 100
        )
        if (maxBundleMetaData.height.get > confirmWindow) {
          totalNumValidBundles = maxBundleMetaData.height.get - confirmWindow
        }

        last100ValidBundleMetaData = if (ancestors.size < confirmWindow + 1) Seq()
        else ancestors.slice(0, ancestors.size - confirmWindow)
        val newTX = last100ValidBundleMetaData.reverse
          .slice(0, confirmWindow).flatMap(_.bundle.extractTX).toSet
        txInMaxBundleNotInValidation = newTX.map{_.hash}
          .filter { h => !last10000ValidTXHash.contains(h) }

        newTX.foreach(t => acceptTransaction(t))

      }
    }

    // Set this to be active for the combiners.
    if (!activeDAGBundles.contains(bundleMetaData) &&
      !bundleMetaData.bundle.extractIds.contains(id)) {
      activeDAGBundles :+= bundleMetaData
    }
  }

  def updateBundleFrom(left: BundleMetaData, right: BundleMetaData): BundleMetaData = {

    val ids = right.bundle.extractIds.map{_.b58}
    val newReps = left.reputations.map { case (id, rep) =>
      id -> {
        if (ids.contains(id)) rep + 1 else rep
      }
    }

    val updatedRight = right.copy(
      height = Some(left.height.get + 1),
      reputations = newReps
    )

    val totalScoreLeft = left.totalScore.get
    val rightScore = updatedRight.bundle.scoreFrom(updatedRight)
    val updatedRightScore = updatedRight.copy(
      totalScore = Some(totalScoreLeft + rightScore)
    )
    db.put(right.bundle.hash, updatedRightScore)
    updateMaxBundle(updatedRightScore)
    updatedRightScore
  }

  def attemptResolveBundle(zero: BundleMetaData, parentHash: String): Unit = {

    val ancestors = findAncestorsUpToLastResolved(parentHash)
    if (!db.contains(zero.bundle.hash)) db.put(zero.bundle.hash, zero)

    if (ancestors.nonEmpty) {

      val chainR = ancestors.tail ++ Seq(zero)

      val chain = chainR.map{
        c =>
          val res = resolveTransactions(c.bundle.extractTXHash.map{_.txHash})
          // println(s"RESOLVE TRANSACTIONS: $res ${c.bundle.hash.slice(0, 5)}")
          c.copy(transactionsResolved = res)
          //if (c.transactionsResolved != res) {
//            db.put(c.bundle.hash, c.copy(transactionsResolved = res))
  //        }
      }

      // TODO: Change to recursion to implement abort-early fold
      chain.fold(ancestors.head) {
        case (left, right) =>

          if (!left.isResolved){
            right
          }
          else if (right.isResolved) right
          else {
            updateBundleFrom(left, right)
          }
      }

    }

   /* println(s"RX on ${id.short} BUNDLE ${zero.bundle.hash.slice(0, 5)} " +
      s"${zero.isResolved} ${zero.height} ${zero.totalScore}" +
      s" ${parentHash.slice(0, 5)} ${db.contains(zero.bundle.hash)} ${ancestors.map{
      a => a.bundle.hash.slice(0, 5) + "_txResolved:" + a.transactionsResolved + "_" + a.totalScore}
      }" )
*/

  }

  def resolveTransactions(txs: Set[String]): Boolean = {
    val missing = txs.filterNot(z => db.contains(z))
    syncPendingTXHashes ++= missing
    missing.isEmpty
  }


  def handleBundle(bundle: Bundle): Unit = {


    if (syncPendingBundleHashes.contains(bundle.hash)) {
      numSyncedBundles += 1
      syncPendingBundleHashes -= bundle.hash
    }

    totalNumBundleMessages += 1
    val parentHash = bundle.extractParentBundleHash.pbHash
    val bmd = db.getAs[BundleMetaData](bundle.hash)
    val notPresent = bmd.isEmpty

    val txs = bundle.extractTXHash.map{_.txHash}
    if (txs.nonEmpty) {

      val txResolved = resolveTransactions(txs)


      val zero = if (notPresent) {
        totalNumNewBundleAdditions += 1
        BundleMetaData(bundle, transactionsResolved = txResolved)
      } else {
        bmd.get.copy(transactionsResolved = txResolved)
      }

      if (zero.isResolved) {
        updateMaxBundle(zero)
      } else {
        attemptResolveBundle(zero, parentHash)
      }
    }

  }
}
