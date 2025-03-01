package views.html
package tournament

import play.api.libs.json.Json

import lila.app.templating.Environment.{ given, * }
import lila.app.ui.ScalatagsTemplate.*
import lila.common.String.html.safeJsonValue
import lila.tournament.Tournament

import controllers.routes

object show:

  def apply(
      tour: Tournament,
      verdicts: lila.gathering.Condition.WithVerdicts,
      data: play.api.libs.json.JsObject,
      chatOption: Option[lila.chat.UserChat.Mine],
      streamers: List[UserId],
      shieldOwner: Option[UserId]
  )(using ctx: WebContext) =
    views.html.base.layout(
      title = s"${tour.name()} #${tour.id}",
      moreJs = frag(
        jsModule("tournament"),
        embedJsUnsafeLoadThen(s"""LichessTournament(${safeJsonValue(
            Json.obj(
              "data"   -> data,
              "i18n"   -> bits.jsI18n(tour),
              "userId" -> ctx.userId,
              "chat" -> chatOption.map { c =>
                chat.json(
                  c.chat,
                  name = trans.chatRoom.txt(),
                  timeout = c.timeout,
                  public = true,
                  resourceId = lila.chat.Chat.ResourceId(s"tournament/${c.chat.id}"),
                  localMod = ctx.userId has tour.createdBy,
                  writeable = !c.locked
                )
              },
              "showRatings" -> ctx.pref.showRatings
            )
          )})""")
      ),
      moreCss = cssTag {
        if (tour.isTeamBattle) "tournament.show.team-battle"
        else "tournament.show"
      },
      chessground = false,
      openGraph = lila.app.ui
        .OpenGraph(
          title = s"${tour.name()}: ${tour.variant.name} ${tour.clock.show} ${tour.mode.name} #${tour.id}",
          url = s"$netBaseUrl${routes.Tournament.show(tour.id).url}",
          description =
            s"${tour.nbPlayers} players compete in the ${showEnglishDate(tour.startsAt)} ${tour.name()}. " +
              s"${tour.clock.show} ${tour.mode.name} games are played during ${tour.minutes} minutes. " +
              tour.winnerId.fold("Winner is not yet decided.") { winnerId =>
                s"${titleNameOrId(winnerId)} takes the prize home!"
              }
        )
        .some,
      csp = defaultCsp.withLilaHttp.some
    )(
      main(cls := s"tour${tour.schedule
          .so { sched =>
            s" tour-sched tour-sched-${sched.freq.name} tour-speed-${sched.speed.name} tour-variant-${sched.variant.key} tour-id-${tour.id}"
          }}")(
        st.aside(cls := "tour__side")(
          tournament.side(tour, verdicts, streamers, shieldOwner, chatOption.isDefined)
        ),
        div(cls := "tour__main")(div(cls := "box")),
        tour.isCreated option div(cls := "tour__faq")(
          faq(tour.mode.rated.some, tour.isPrivate.option(tour.id))
        )
      )
    )
