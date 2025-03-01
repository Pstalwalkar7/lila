package lila.report

import lila.user.Me
import lila.common.Iso

sealed trait Reason:

  def key = toString.toLowerCase

  def name = toString

object Reason:

  case object Cheat extends Reason
  case object CheatPrint extends Reason: // BC, replaced with AltPrint
    override def name = "Print"
  case object AltPrint extends Reason:
    override def name = "Print"
  case object Comm extends Reason:
    def flagText = "[FLAG]"
  case object Boost    extends Reason
  case object Other    extends Reason
  case object Playbans extends Reason

  val all   = List(Cheat, AltPrint, Comm, Boost, Other, CheatPrint)
  val keys  = all.map(_.key)
  val byKey = all.mapBy(_.key)

  given Iso.StringIso[Reason] = Iso.string(k => byKey.getOrElse(k, Other), _.key)

  def apply(key: String): Option[Reason] = byKey get key

  trait WithReason:
    def reason: Reason

    def isCheat    = reason == Cheat
    def isOther    = reason == Other
    def isPrint    = reason == AltPrint || reason == CheatPrint
    def isComm     = reason == Comm
    def isBoost    = reason == Boost
    def isPlaybans = reason == Playbans

  def isGrantedFor(mod: Me)(reason: Reason) =
    import lila.security.Granter
    reason match
      case Cheat                                    => Granter.is(_.MarkEngine)(mod)
      case AltPrint | CheatPrint | Playbans | Other => Granter.is(_.Admin)(mod)
      case Comm                                     => Granter.is(_.Shadowban)(mod)
      case Boost                                    => Granter.is(_.MarkBooster)(mod)
