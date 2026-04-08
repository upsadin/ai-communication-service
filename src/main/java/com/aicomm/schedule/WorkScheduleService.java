package com.aicomm.schedule;

import com.aicomm.telegram.TelegramAuthManager;
import it.tdlight.jni.TdApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages work schedule: online/offline status and working hours check.
 *
 * - Toggles Telegram online status at work-start / work-end
 * - Provides isWorkingHours() for other services to decide whether to process now or defer
 * - Calculates delay for deferred messages (random time after next work-start)
 */
@Slf4j
@Service
@EnableConfigurationProperties(WorkScheduleProperties.class)
public class WorkScheduleService {

    private final WorkScheduleProperties props;
    private final TelegramAuthManager authManager;

    public WorkScheduleService(WorkScheduleProperties props, @Lazy TelegramAuthManager authManager) {
        this.props = props;
        this.authManager = authManager;
    }

    /**
     * Checks every minute if we need to toggle online status.
     */
    @Scheduled(fixedRate = 60_000)
    public void checkAndToggleOnlineStatus() {
        if (!authManager.isReady()) return;

        boolean shouldBeOnline = isWorkingHours();
        setOnlineStatus(shouldBeOnline);
    }

    /**
     * Is it currently within working hours?
     */
    public boolean isWorkingHours() {
        var now = ZonedDateTime.now(props.timezone());
        var dayOfWeek = now.getDayOfWeek();
        var time = now.toLocalTime();

        if (!props.workDays().contains(dayOfWeek)) {
            return false;
        }

        return !time.isBefore(props.workStart()) && !time.isAfter(props.workEnd());
    }

    /**
     * Calculates delay in ms until a deferred message should be processed.
     * Returns random time between (next work-start + morningDelayMin) and (next work-start + morningDelayMax).
     * Returns 0 if currently within working hours.
     */
    public long calculateDeferralDelay() {
        if (isWorkingHours()) return 0;

        var now = ZonedDateTime.now(props.timezone());
        var nextWorkStart = findNextWorkStart(now);
        long msUntilWorkStart = java.time.Duration.between(now, nextWorkStart).toMillis();

        long randomMorningDelay = ThreadLocalRandom.current()
                .nextLong(props.morningDelayMinMs(), props.morningDelayMaxMs() + 1);

        long totalDelay = msUntilWorkStart + randomMorningDelay;

        log.debug("Deferral: now={}, nextWorkStart={}, delay={}ms ({}min)",
                now.toLocalTime(), nextWorkStart.toLocalTime(), totalDelay, totalDelay / 60000);

        return totalDelay;
    }

    private ZonedDateTime findNextWorkStart(ZonedDateTime from) {
        var candidate = from.toLocalDate().atTime(props.workStart()).atZone(props.timezone());

        // If today is a work day and we haven't passed work-start yet, use today
        if (props.workDays().contains(from.getDayOfWeek()) && from.toLocalTime().isBefore(props.workStart())) {
            return candidate;
        }

        // Otherwise find next work day
        candidate = candidate.plusDays(1);
        while (!props.workDays().contains(candidate.getDayOfWeek())) {
            candidate = candidate.plusDays(1);
        }

        return candidate;
    }

    private void setOnlineStatus(boolean online) {
        var option = new TdApi.SetOption("online",
                new TdApi.OptionValueBoolean(online));
        authManager.getClient().send(option, result -> {
            if (result.isError()) {
                log.debug("Failed to set online={}: {}", online, result.getError().message);
            }
        });
    }
}
