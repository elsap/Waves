package com.wavesplatform.history

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock

import com.wavesplatform.features.{BlockchainFeatures, FeatureProvider, BlockchainFeatureStatus}
import com.wavesplatform.settings.{FeaturesSettings, FunctionalitySettings}
import com.wavesplatform.state2.{BlockDiff, ByteStr, DataTypes}
import com.wavesplatform.utils._

import scala.collection.JavaConverters._
import kamon.Kamon
import scorex.block.Block
import scorex.transaction.History.BlockchainScore
import scorex.transaction.ValidationError.GenericError
import scorex.transaction._
import scorex.utils.{LogMVMapBuilder, ScorexLogging}

import scala.util.Try

class HistoryWriterImpl private(file: Option[File], val synchronizationToken: ReentrantReadWriteLock,
                                functionalitySettings: FunctionalitySettings, featuresSettings: FeaturesSettings)
  extends History with FeatureProvider with ScorexLogging {

  import HistoryWriterImpl._

  override val ActivationWindowSize: Int = functionalitySettings.featureCheckBlocksPeriod
  override val MinVotesWithinWindowToActivateFeature: Int = functionalitySettings.blocksForFeatureActivation

  private val db = createMVStore(file)
  private val blockBodyByHeight = Synchronized(db.openMap("blocks", new LogMVMapBuilder[Int, Array[Byte]]))
  private val blockIdByHeight = Synchronized(db.openMap("signatures", new LogMVMapBuilder[Int, ByteStr].valueType(DataTypes.byteStr)))
  private val heightByBlockId = Synchronized(db.openMap("signaturesReverse", new LogMVMapBuilder[ByteStr, Int].keyType(DataTypes.byteStr)))
  private val scoreByHeight = Synchronized(db.openMap("score", new LogMVMapBuilder[Int, BigInt]))
  private val featuresVotes = Synchronized(db.openMap("features-votes", new LogMVMapBuilder[Int, Map[Short, Int]].valueType(DataTypes.mapShortInt)))
  private val featuresState = Synchronized(db.openMap("features-state", new LogMVMapBuilder[Short, Int]))

  private[HistoryWriterImpl] def isConsistent: Boolean = read { implicit l =>
    // check if all maps have same size
    Set(blockBodyByHeight().size(), blockIdByHeight().size(), heightByBlockId().size(), scoreByHeight().size()).size == 1
  }

  private def displayFeatures(s: Set[Short]): String = s"FEATURE${if (s.size > 1) "S"} ${s.mkString(", ")} ${if (s.size > 1) "WERE" else "WAS"}"

  def featureVotesCountWithinActivationWindow(height: Int): Map[Short, Int] = read { implicit lock =>
    featuresVotes().get(activationWindowOpeningFromHeight(height))
  }

  private def acceptedFeaturesInActivationWindow(height: Int): Set[Short] = {
    featureVotesCountWithinActivationWindow(height)
      .filter(p => p._2 >= MinVotesWithinWindowToActivateFeature)
      .keySet
  }

  private def updateFeaturesState(height: Int): Unit = write { implicit lock =>
    require(height % ActivationWindowSize == 0, s"Features' state can't be updated at given height: $height")

    val acceptedFeatures = acceptedFeaturesInActivationWindow(height)

    val unimplementedAccepted = acceptedFeatures.diff(BlockchainFeatures.implemented)
    if (unimplementedAccepted.nonEmpty) {
      log.warn(s"UNIMPLEMENTED ${displayFeatures(unimplementedAccepted)} ACCEPTED ON BLOCKCHAIN")
      log.warn("PLEASE, UPDATE THE NODE AS SOON AS POSSIBLE")
      log.warn("OTHERWISE THE NODE WILL BE STOPPED OR FORKED UPON FEATURE ACTIVATION")
    }

    val activatedFeatures = featuresState().entrySet().asScala.filter(_.getValue <= height - ActivationWindowSize).map(_.getKey).toSet

    val unimplementedActivated = activatedFeatures.diff(BlockchainFeatures.implemented)
    if (unimplementedActivated.nonEmpty) {
      log.error(s"UNIMPLEMENTED ${displayFeatures(unimplementedActivated)} ACTIVATED ON BLOCKCHAIN")
      log.error("PLEASE, UPDATE THE NODE IMMEDIATELY")
      if (featuresSettings.autoShutdownOnUnsupportedFeature) {
        log.error("FOR THIS REASON THE NODE WAS STOPPED AUTOMATICALLY")
        forceStopApplication(UnsupportedFeature)
      }
      else log.error("OTHERWISE THE NODE WILL END UP ON A FORK")
    }

    featuresState.mutate(_.putAll(acceptedFeatures.diff(featuresState().keySet.asScala).map(_ -> height).toMap.asJava))
  }

  private def alterVotes(height: Int, votes: Set[Short], voteMod: Int): Unit = write { implicit lock =>
    val votingWindowOpening = activationWindowOpeningFromHeight(height)
    val votesWithinWindow = featuresVotes().getOrDefault(votingWindowOpening, Map.empty[Short, Int])
    val newVotes = votes.foldLeft(votesWithinWindow)((v, feature) => v + (feature -> (v.getOrElse(feature, 0) + voteMod)))
    featuresVotes.mutate(_.put(votingWindowOpening, newVotes))
  }

  def appendBlock(block: Block)(consensusValidation: => Either[ValidationError, BlockDiff]): Either[ValidationError, BlockDiff] =
    write { implicit lock =>

      assert(block.signatureValid)

      if ((height() == 0) || (this.lastBlock.get.uniqueId == block.reference)) consensusValidation.map { blockDiff =>
        val h = height() + 1
        val score = (if (height() == 0) BigInt(0) else this.score()) + block.blockScore
        blockBodyByHeight.mutate(_.put(h, block.bytes))
        scoreByHeight.mutate(_.put(h, score))
        blockIdByHeight.mutate(_.put(h, block.uniqueId))
        heightByBlockId.mutate(_.put(block.uniqueId, h))

        alterVotes(h, block.supportedFeaturesIds, 1)

        if (h % ActivationWindowSize == 0) updateFeaturesState(h)

        db.commit()
        blockHeightStats.record(h)
        blockSizeStats.record(block.bytes.length)
        transactionsInBlockStats.record(block.transactionData.size)

        if (h % 100 == 0) db.compact(CompactFillRate, CompactMemorySize)

        log.trace(s"Full Block(id=${block.uniqueId},txs_count=${block.transactionData.size}) persisted")
        blockDiff
      }
      else {
        Left(GenericError(s"Parent ${block.reference} of block ${block.uniqueId} does not match last block ${this.lastBlock.map(_.uniqueId)}"))
      }
    }

  def discardBlock(): Seq[Transaction] = write { implicit lock =>
    val h = height()

    alterVotes(h, blockAt(h).map(b => b.supportedFeaturesIds).getOrElse(Set.empty), -1)

    val transactions =
      Block.parseBytes(blockBodyByHeight.mutate(_.remove(h))).fold(_ => Seq.empty[Transaction], _.transactionData)
    scoreByHeight.mutate(_.remove(h))

    if (h % ActivationWindowSize == 0) {
      val featuresToRemove = featuresState().entrySet().asScala.filter(_.getValue == h).map(_.getKey)
      featuresState.mutate(fs => featuresToRemove.foreach(fs.remove))
    }

    val vOpt = Option(blockIdByHeight.mutate(_.remove(h)))
    vOpt.map(v => heightByBlockId.mutate(_.remove(v)))
    db.commit()

    transactions
  }

  override def lastBlockIds(howMany: Int): Seq[ByteStr] = read { implicit lock =>
    (Math.max(1, height() - howMany + 1) to height()).flatMap(i => Option(blockIdByHeight().get(i)))
      .reverse
  }

  override def height(): Int = read { implicit lock => blockIdByHeight().size() }

  override def scoreOf(id: ByteStr): Option[BlockchainScore] = read { implicit lock =>
    heightOf(id).map(scoreByHeight().get(_))
  }

  override def heightOf(blockSignature: ByteStr): Option[Int] = read { implicit lock =>
    Option(heightByBlockId().get(blockSignature))
  }

  override def blockBytes(height: Int): Option[Array[Byte]] = read { implicit lock =>
    Option(blockBodyByHeight().get(height))
  }

  override def close(): Unit = db.close()

  override def featureActivationHeight(feature: Short): Option[Int] = read { implicit lock =>
    Option(featuresState().get(feature)).map(h => h + ActivationWindowSize)
  }

  override def featureStatus(feature: Short): BlockchainFeatureStatus = read { implicit lock =>
    Option(featuresState().get(feature))
      .map(h => if (h <= height - ActivationWindowSize)
        BlockchainFeatureStatus.Activated else
        BlockchainFeatureStatus.Accepted)
      .getOrElse(BlockchainFeatureStatus.Undefined)
  }

  override def activationWindowOpeningFromHeight(height: Int): Int = {
    val r = 1 + height - height % ActivationWindowSize
    if (r <= height) r else r - ActivationWindowSize
  }

  override def lastBlockTimestamp(): Option[Long] = this.lastBlock.map(_.timestamp)

  override def lastBlockId(): Option[ByteStr] = this.lastBlock.map(_.signerData.signature)

  override def blockAt(height: Int): Option[Block] = blockBytes(height).map(Block.parseBytes(_).get)
}

object HistoryWriterImpl extends ScorexLogging {
  private val CompactFillRate = 90
  private val CompactMemorySize = 10 * 1024 * 1024

  def apply(file: Option[File], synchronizationToken: ReentrantReadWriteLock, functionalitySettings: FunctionalitySettings,
            featuresSettings: FeaturesSettings): Try[HistoryWriterImpl] =
    createWithStore[HistoryWriterImpl](file, new HistoryWriterImpl(file, synchronizationToken, functionalitySettings, featuresSettings), h => h.isConsistent)

  private val blockHeightStats = Kamon.metrics.histogram("block-height")
  private val blockSizeStats = Kamon.metrics.histogram("block-size-bytes")
  private val transactionsInBlockStats = Kamon.metrics.histogram("transactions-in-block")
}
