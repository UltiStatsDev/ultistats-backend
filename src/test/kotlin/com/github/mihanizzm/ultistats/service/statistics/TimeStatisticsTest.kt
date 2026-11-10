package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.MatchAbstractTest
import com.github.mihanizzm.ultistats.model.events.AnnouncementStartEvent
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.SystemEvent
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.model.statistics.AnnouncementStatistics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@Suppress("NonAsciiCharacters")
class TimeStatisticsTest : MatchAbstractTest() {
    private fun ts(sec: Long): Instant = Instant.parse(START_DATE).plusSeconds(sec)

    @BeforeEach
    fun setup() {
        MATCH.events.clear()
        matchService.update(MATCH)
    }

    @Test
    fun `only closed announcement pairs contribute to the canonical time aggregate`() {
        val team = UUIDS[0]
        MATCH.events.addAll(listOf(
            OnePlayerEvent(UUIDS[2], ts(0), EventType.PULL),
            AnnouncementStartEvent(team, ts(1)),
            SystemEvent(ts(3), EventType.ANNOUNCEMENT_END),
            AnnouncementStartEvent(null, ts(4)),
            SystemEvent(ts(4), EventType.ANNOUNCEMENT_END),
            AnnouncementStartEvent(team, ts(5)),
            SystemEvent(ts(8), EventType.ANNOUNCEMENT_END),
            AnnouncementStartEvent(null, ts(9)),
        ))

        val time = recalculateTestMatchStatistics().timeStatistics
        assertThat(time.announcements).isEqualTo(mapOf(
            team to AnnouncementStatistics(2, Duration.ofSeconds(5)),
            null to AnnouncementStatistics(1, Duration.ZERO),
        ))
        assertThat(time.totalTime).isEqualTo(Duration.ofSeconds(9))
        assertThat(time.pureGameTime).isEqualTo(Duration.ofSeconds(4))
    }

    @Test
    fun `Владение только у атакующей команды, игроки получают свое время`() {
        // Пулл (TEAM_1) -> Подбор (TEAM_2) -> Пас (TEAM_2) -> Гол (TEAM_2)
        MATCH.events.add(OnePlayerEvent(UUIDS[2], ts(0), EventType.PULL))
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(10), EventType.PICKUP))
        MATCH.events.add(TwoPlayerEvent(UUIDS[7], UUIDS[8], ts(25), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[8], UUIDS[9], ts(40), EventType.GOAL))

        val stats = recalculateTestMatchStatistics()

        val team1 = stats.teamStatistics.first { it.teamId == UUIDS[0] }.time
        val team2 = stats.teamStatistics.first { it.teamId == UUIDS[1] }.time
        assertThat(team1.totalPossessionTime).isEqualTo(Duration.ofSeconds(0))
        assertThat(team2.totalPossessionTime).isEqualTo(Duration.ofSeconds(30)) // 10..40

        val p7 = stats.playerStatistics.first { it.participantId == UUIDS[7] }.time
        val p8 = stats.playerStatistics.first { it.participantId == UUIDS[8] }.time
        assertThat(p7.totalPossessionTime).isEqualTo(Duration.ofSeconds(15)) // 10..25
        assertThat(p8.totalPossessionTime).isEqualTo(Duration.ofSeconds(15)) // 25..40
    }

    @Test
    fun `Таймаут учитывается в общих и командных таймингах`() {
        // Пулл -> Подбор -> Таймаут -> Конец таймаута -> Пас -> Гол
        MATCH.events.add(OnePlayerEvent(UUIDS[2], ts(0), EventType.PULL))
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(10), EventType.PICKUP))
        MATCH.events.add(TeamEvent(UUIDS[1], ts(15), EventType.TIMEOUT_START))
        MATCH.events.add(TeamEvent(UUIDS[1], ts(75), EventType.TIMEOUT_END)) // 60 сек таймаута
        MATCH.events.add(TwoPlayerEvent(UUIDS[7], UUIDS[8], ts(90), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[8], UUIDS[9], ts(100), EventType.GOAL))

        val stats = recalculateTestMatchStatistics()

        // Матчевая статистика по времени
        assertThat(stats.timeStatistics.timeSpentOnTimeouts).isEqualTo(Duration.ofSeconds(60))

        // Командная статистика по времени таймаутов
        val team2 = stats.teamStatistics.first { it.teamId == UUIDS[1] }.time
        assertThat(team2.totalTimeSpentOnTimeouts).isEqualTo(Duration.ofSeconds(60))

        // Время владения команды не включает таймаут
        assertThat(team2.totalPossessionTime).isEqualTo(Duration.ofSeconds(30)) // 10..15 и 75..100
    }

    @Test
    fun `Время между очками увеличивается у команды со вторым пуллом`() {
        // Первый поинт
        MATCH.events.add(OnePlayerEvent(UUIDS[2], ts(0), EventType.PULL))
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(10), EventType.PICKUP))
        MATCH.events.add(TwoPlayerEvent(UUIDS[7], UUIDS[8], ts(20), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[8], UUIDS[9], ts(30), EventType.GOAL))
        // Второй пулл делает команда забившая гол (TEAM_2)
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(50), EventType.PULL)) // между очками 20 сек

        val stats = recalculateTestMatchStatistics()
        val team1 = stats.teamStatistics.first { it.teamId == UUIDS[0] }.time
        val team2 = stats.teamStatistics.first { it.teamId == UUIDS[1] }.time

        assertThat(team1.totalTimeBetweenPoints).isEqualTo(Duration.ZERO)
        assertThat(team2.totalTimeBetweenPoints).isEqualTo(Duration.ofSeconds(20)) // 30..50
    }

    @Test
    fun `Халфтайм учитывается и не попадает во время между очками`() {
        // Поинт до халфтайма
        MATCH.events.add(OnePlayerEvent(UUIDS[2], ts(0), EventType.PULL))
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(10), EventType.PICKUP))
        MATCH.events.add(TwoPlayerEvent(UUIDS[7], UUIDS[8], ts(20), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[8], UUIDS[9], ts(30), EventType.GOAL))
        // Халфтайм 60 сек
        MATCH.events.add(SystemEvent(ts(31), EventType.HALFTIME_START))
        MATCH.events.add(SystemEvent(ts(91), EventType.HALFTIME_END))
        // Новый поинт после халфтайма
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(100), EventType.PULL)) // между очками после халфтайма: 91..100 = 9 сек
        MATCH.events.add(OnePlayerEvent(UUIDS[3], ts(110), EventType.PICKUP))
        MATCH.events.add(TwoPlayerEvent(UUIDS[3], UUIDS[2], ts(120), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[2], UUIDS[5], ts(130), EventType.GOAL))

        val stats = recalculateTestMatchStatistics()

        assertThat(stats.timeStatistics.timeSpentOnHalftime).isEqualTo(Duration.ofSeconds(60))

        val team2 = stats.teamStatistics.first { it.teamId == UUIDS[1] }.time
        assertThat(team2.totalTimeBetweenPoints).isEqualTo(Duration.ofSeconds(9)) // только после HALFTIME_END
    }

    @Test
    fun `Чистое время исключает между поинтами, таймауты и халфтайм`() {
        // Поинт 1 с таймаутом
        MATCH.events.add(OnePlayerEvent(UUIDS[2], ts(0), EventType.PULL))
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(10), EventType.PICKUP))
        MATCH.events.add(TeamEvent(UUIDS[1], ts(20), EventType.TIMEOUT_START))
        MATCH.events.add(TeamEvent(UUIDS[1], ts(80), EventType.TIMEOUT_END)) // 60
        MATCH.events.add(TwoPlayerEvent(UUIDS[7], UUIDS[8], ts(90), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[8], UUIDS[9], ts(100), EventType.GOAL))
        // Между поинтами
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(130), EventType.PULL)) // 30
        // Поинт 2 без остановок
        MATCH.events.add(OnePlayerEvent(UUIDS[3], ts(140), EventType.PICKUP))
        MATCH.events.add(TwoPlayerEvent(UUIDS[3], UUIDS[2], ts(150), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[2], UUIDS[5], ts(160), EventType.GOAL))

        val stats = recalculateTestMatchStatistics()

        assertThat(stats.timeStatistics.timeSpentOnTimeouts).isEqualTo(Duration.ofSeconds(60))
        assertThat(stats.timeStatistics.timeSpentBetweenPoints).isEqualTo(Duration.ofSeconds(30))
        assertThat(stats.timeStatistics.timeSpentOnHalftime).isEqualTo(Duration.ZERO)

        val expectedPure = Duration.ofSeconds(160) // суммарное время между всеми событиями
            .minus(Duration.ofSeconds(30)) // между поинтами
            .minus(Duration.ofSeconds(60)) // таймауты
            .minus(Duration.ZERO) // халфтайм
        assertThat(stats.timeStatistics.pureGameTime).isEqualTo(expectedPure)
    }

    @Test
    fun `Между поинтами не включает халфтайм (минимальная последовательность)`() {
        // Поинт завершен голом
        MATCH.events.add(OnePlayerEvent(UUIDS[2], ts(0), EventType.PULL))
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(10), EventType.PICKUP))
        MATCH.events.add(TwoPlayerEvent(UUIDS[7], UUIDS[8], ts(20), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[8], UUIDS[9], ts(30), EventType.GOAL))
        // Халфтайм 60 сек сразу после поинта
        MATCH.events.add(SystemEvent(ts(31), EventType.HALFTIME_START))
        MATCH.events.add(SystemEvent(ts(91), EventType.HALFTIME_END))
        // Следующий пулл (время между поинтами должно считаться только после HALFTIME_END)
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(95), EventType.PULL)) // 91..95 = 4 сек

        val stats = recalculateTestMatchStatistics()

        assertThat(stats.timeStatistics.timeSpentOnHalftime).isEqualTo(Duration.ofSeconds(60))
        val team2 = stats.teamStatistics.first { it.teamId == UUIDS[1] }.time
        assertThat(team2.totalTimeBetweenPoints).isEqualTo(Duration.ofSeconds(4))
    }

    @Test
    fun `Время игроков в длинной цепочке пасов считается корректно`() {
        // Пулл -> Подбор -> несколько пасов -> Гол
        MATCH.events.add(OnePlayerEvent(UUIDS[2], ts(0), EventType.PULL))
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(5), EventType.PICKUP))
        MATCH.events.add(TwoPlayerEvent(UUIDS[7], UUIDS[8], ts(15), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[8], UUIDS[9], ts(25), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[9], UUIDS[10], ts(35), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[10], UUIDS[11], ts(50), EventType.GOAL))

        val stats = recalculateTestMatchStatistics()
        fun playerTime(idIdx: Int) = stats.playerStatistics.first { it.participantId == UUIDS[idIdx] }.time.totalPossessionTime

        assertThat(playerTime(7)).isEqualTo(Duration.ofSeconds(10)) // 5..15
        assertThat(playerTime(8)).isEqualTo(Duration.ofSeconds(10)) // 15..25
        assertThat(playerTime(9)).isEqualTo(Duration.ofSeconds(10)) // 25..35
        assertThat(playerTime(10)).isEqualTo(Duration.ofSeconds(15)) // 35..50
    }

    @Test
    fun `Время владения не начисляется между дропом и подбором другой командой`() {
        // Пулл -> Подбор (TEAM_2) -> Пас -> Дроп -> Подбор (TEAM_1) -> Пас -> Гол
        MATCH.events.add(OnePlayerEvent(UUIDS[2], ts(0), EventType.PULL))
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(10), EventType.PICKUP))
        MATCH.events.add(TwoPlayerEvent(UUIDS[7], UUIDS[8], ts(20), EventType.PASS))
        MATCH.events.add(OnePlayerEvent(UUIDS[8], ts(25), EventType.INCOMPLETE_PASS)) // владение никому до следующего подбора
        MATCH.events.add(OnePlayerEvent(UUIDS[3], ts(40), EventType.PICKUP))
        MATCH.events.add(TwoPlayerEvent(UUIDS[3], UUIDS[2], ts(50), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[2], UUIDS[5], ts(60), EventType.GOAL))

        val stats = recalculateTestMatchStatistics()
        val team1 = stats.teamStatistics.first { it.teamId == UUIDS[0] }.time
        val team2 = stats.teamStatistics.first { it.teamId == UUIDS[1] }.time

        assertThat(team2.totalPossessionTime).isEqualTo(Duration.ofSeconds(15)) // 10..25
        assertThat(team1.totalPossessionTime).isEqualTo(Duration.ofSeconds(20)) // 40..60
    }

    @Test
    fun `Пулл не дает владение до подбора диска другой командой`() {
        // Пулл (TEAM_1) -> длительная пауза -> Подбор (TEAM_2) -> Пас -> Гол
        MATCH.events.add(OnePlayerEvent(UUIDS[2], ts(0), EventType.PULL))
        MATCH.events.add(OnePlayerEvent(UUIDS[7], ts(30), EventType.PICKUP))
        MATCH.events.add(TwoPlayerEvent(UUIDS[7], UUIDS[8], ts(50), EventType.PASS))
        MATCH.events.add(TwoPlayerEvent(UUIDS[8], UUIDS[9], ts(60), EventType.GOAL))

        val stats = recalculateTestMatchStatistics()
        val team1 = stats.teamStatistics.first { it.teamId == UUIDS[0] }.time
        val team2 = stats.teamStatistics.first { it.teamId == UUIDS[1] }.time

        assertThat(team1.totalPossessionTime).isEqualTo(Duration.ZERO) // после пулла до подбора владения нет
        assertThat(team2.totalPossessionTime).isEqualTo(Duration.ofSeconds(30)) // 30..60
    }
}
