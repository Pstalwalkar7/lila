package views.html
package tournament

import lila.app.templating.Environment.{ given, * }
import lila.app.ui.ScalatagsTemplate.{ *, given }

import controllers.routes

object faq:

  import trans.arena.*

  def page(using WebContext) =
    views.html.base.layout(
      title = trans.tournamentFAQ.txt(),
      moreCss = cssTag("page")
    ) {
      main(cls := "page-small box box-pad page")(
        boxTop(
          h1(
            a(href := routes.Tournament.home, dataIcon := licon.LessThan, cls := "text"),
            trans.tournamentFAQ()
          )
        ),
        div(cls := "body")(apply())
      )
    }

  def apply(rated: Option[Boolean] = None, privateId: Option[String] = None)(using WebContext) =
    frag(
      privateId.map { id =>
        frag(
          h2(trans.arena.thisIsPrivate()),
          p(trans.arena.shareUrl(s"$netBaseUrl${routes.Tournament.show(id)}")) // XXX
        )
      },
      p(trans.arena.willBeNotified()),
      h2(trans.arena.isItRated()),
      rated match {
        case Some(true)  => p(trans.arena.isRated())
        case Some(false) => p(trans.arena.isNotRated())
        case None        => p(trans.arena.someRated())
      },
      h2(howAreScoresCalculated()),
      p(howAreScoresCalculatedAnswer()),
      h2(berserk()),
      p(berserkAnswer()),
      h2(howIsTheWinnerDecided()),
      p(howIsTheWinnerDecidedAnswer()),
      h2(howDoesPairingWork()),
      p(howDoesPairingWorkAnswer()),
      h2(howDoesItEnd()),
      p(howDoesItEndAnswer()),
      h2(otherRules()),
      p(thereIsACountdown()),
      p(drawingWithinNbMoves.pluralSame(10)),
      p(drawStreakStandard(30)),
      p(drawStreakVariants()),
      table(cls := "slist slist-pad")(
        thead(
          tr(
            th(variant()),
            th(minimumGameLength())
          )
        ),
        tbody(
          tr(
            td(trans.standard(), ", Chess960, Horde"),
            td(30)
          ),
          tr(
            td("Antichess, Crazyhouse, King of the Hill"),
            td(20)
          ),
          tr(
            td("Three check, Atomic, Racing Kings"),
            td(10)
          )
        )
      )
    )
