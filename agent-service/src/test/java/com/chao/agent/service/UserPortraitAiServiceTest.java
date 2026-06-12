package com.chao.agent.service;

import com.chao.common.dto.AiPortraitResult;
import com.chao.common.dto.SchedulePreferenceDto;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class UserPortraitAiServiceTest {

    private UserPortraitAiService createService() {
        return new UserPortraitAiService(null, null, null);
    }

    // ── clampPortraitResult: morningPersonScore ──

    @Test
    void clamp_morningPersonScore_inRange() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setMorningPersonScore(50);
        invokeClamp(r);
        assertEquals(50, r.getMorningPersonScore());
    }

    @Test
    void clamp_morningPersonScore_belowZero_clampedTo0() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setMorningPersonScore(-10);
        invokeClamp(r);
        assertEquals(0, r.getMorningPersonScore());
    }

    @Test
    void clamp_morningPersonScore_above100_clampedTo100() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setMorningPersonScore(999);
        invokeClamp(r);
        assertEquals(100, r.getMorningPersonScore());
    }

    @Test
    void clamp_morningPersonScore_null_unchanged() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        invokeClamp(r);
        assertNull(r.getMorningPersonScore());
    }

    // ── clampPortraitResult: focusDurationAvg ──

    @Test
    void clamp_focusDurationAvg_inRange() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setFocusDurationAvg(45);
        invokeClamp(r);
        assertEquals(45, r.getFocusDurationAvg());
    }

    @Test
    void clamp_focusDurationAvg_below15_clampedTo15() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setFocusDurationAvg(10);
        invokeClamp(r);
        assertEquals(15, r.getFocusDurationAvg());
    }

    @Test
    void clamp_focusDurationAvg_above120_clampedTo120() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setFocusDurationAvg(999);
        invokeClamp(r);
        assertEquals(120, r.getFocusDurationAvg());
    }

    @Test
    void clamp_focusDurationAvg_null_unchanged() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        invokeClamp(r);
        assertNull(r.getFocusDurationAvg());
    }

    // ── clampPortraitResult: procrastinationIndex ──

    @Test
    void clamp_procrastinationIndex_inRange() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setProcrastinationIndex(0.5);
        invokeClamp(r);
        assertEquals(0.5, r.getProcrastinationIndex(), 0.001);
    }

    @Test
    void clamp_procrastinationIndex_negative_clampedTo0() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setProcrastinationIndex(-0.5);
        invokeClamp(r);
        assertEquals(0.0, r.getProcrastinationIndex(), 0.001);
    }

    @Test
    void clamp_procrastinationIndex_above1_clampedTo1() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setProcrastinationIndex(1.5);
        invokeClamp(r);
        assertEquals(1.0, r.getProcrastinationIndex(), 0.001);
    }

    @Test
    void clamp_procrastinationIndex_null_unchanged() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        invokeClamp(r);
        assertNull(r.getProcrastinationIndex());
    }

    // ── clampPortraitResult: recommendation ──

    @Test
    void clamp_recommendation_null_unchanged() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        invokeClamp(r);
        assertNull(r.getRecommendation());
    }

    @Test
    void clamp_recommendation_focusMinutesValid30() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        rec.setFocusMinutes(30);
        r.setRecommendation(rec);
        invokeClamp(r);
        assertEquals(30, r.getRecommendation().getFocusMinutes());
    }

    @Test
    void clamp_recommendation_focusMinutesValid45() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        rec.setFocusMinutes(45);
        r.setRecommendation(rec);
        invokeClamp(r);
        assertEquals(45, r.getRecommendation().getFocusMinutes());
    }

    @Test
    void clamp_recommendation_focusMinutesValid60() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        rec.setFocusMinutes(60);
        r.setRecommendation(rec);
        invokeClamp(r);
        assertEquals(60, r.getRecommendation().getFocusMinutes());
    }

    @Test
    void clamp_recommendation_focusMinutesInvalid_clampedTo45() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        rec.setFocusMinutes(999);
        r.setRecommendation(rec);
        invokeClamp(r);
        assertEquals(45, r.getRecommendation().getFocusMinutes());
    }

    @Test
    void clamp_recommendation_focusMinutesNull_unchanged() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        r.setRecommendation(rec);
        invokeClamp(r);
        assertNull(r.getRecommendation().getFocusMinutes());
    }

    @Test
    void clamp_recommendation_breakMinutes_always10() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        rec.setBreakMinutes(30);
        r.setRecommendation(rec);
        invokeClamp(r);
        assertEquals(10, r.getRecommendation().getBreakMinutes());
    }

    @Test
    void clamp_recommendation_maxDailyMinutes_inRange() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        rec.setMaxDailyMinutes(240);
        r.setRecommendation(rec);
        invokeClamp(r);
        assertEquals(240, r.getRecommendation().getMaxDailyMinutes());
    }

    @Test
    void clamp_recommendation_maxDailyMinutes_below120_clampedTo120() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        rec.setMaxDailyMinutes(60);
        r.setRecommendation(rec);
        invokeClamp(r);
        assertEquals(120, r.getRecommendation().getMaxDailyMinutes());
    }

    @Test
    void clamp_recommendation_maxDailyMinutes_above300_clampedTo300() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        rec.setMaxDailyMinutes(999);
        r.setRecommendation(rec);
        invokeClamp(r);
        assertEquals(300, r.getRecommendation().getMaxDailyMinutes());
    }

    @Test
    void clamp_recommendation_maxDailyMinutes_null_unchanged() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        r.setRecommendation(rec);
        invokeClamp(r);
        assertNull(r.getRecommendation().getMaxDailyMinutes());
    }

    // ── clampPortraitResult: tips pass-through ──

    @Test
    void clamp_tips_preserved() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setTips(List.of("保持专注", "每日复习", "早睡早起"));
        invokeClamp(r);
        assertEquals(3, r.getTips().size());
        assertEquals("保持专注", r.getTips().get(0));
        assertEquals("每日复习", r.getTips().get(1));
    }

    // ── clampPortraitResult: full integration ──

    @Test
    void clamp_fullResult_allOutOfRange() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setMorningPersonScore(999);
        r.setFocusDurationAvg(999);
        r.setProcrastinationIndex(999.0);
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        rec.setFocusMinutes(999);
        rec.setBreakMinutes(30);
        rec.setMaxDailyMinutes(999);
        r.setRecommendation(rec);
        r.setTips(List.of("test"));

        invokeClamp(r);

        assertEquals(100, r.getMorningPersonScore());
        assertEquals(120, r.getFocusDurationAvg());
        assertEquals(1.0, r.getProcrastinationIndex(), 0.001);
        assertEquals(45, r.getRecommendation().getFocusMinutes());
        assertEquals(10, r.getRecommendation().getBreakMinutes());
        assertEquals(300, r.getRecommendation().getMaxDailyMinutes());
    }

    @Test
    void clamp_fullResult_allInRange() throws Exception {
        AiPortraitResult r = new AiPortraitResult();
        r.setMorningPersonScore(50);
        r.setFocusDurationAvg(45);
        r.setProcrastinationIndex(0.3);
        SchedulePreferenceDto rec = new SchedulePreferenceDto();
        rec.setFocusMinutes(45);
        rec.setBreakMinutes(10);
        rec.setMaxDailyMinutes(240);
        r.setRecommendation(rec);
        r.setTips(List.of("保持专注"));

        invokeClamp(r);

        assertEquals(50, r.getMorningPersonScore());
        assertEquals(45, r.getFocusDurationAvg());
        assertEquals(0.3, r.getProcrastinationIndex(), 0.001);
        assertEquals(45, r.getRecommendation().getFocusMinutes());
        assertEquals(10, r.getRecommendation().getBreakMinutes());
        assertEquals(240, r.getRecommendation().getMaxDailyMinutes());
    }

    // ── helper ──

    private void invokeClamp(AiPortraitResult r) throws Exception {
        UserPortraitAiService service = createService();
        Method m = UserPortraitAiService.class.getDeclaredMethod("clampPortraitResult", AiPortraitResult.class);
        m.setAccessible(true);
        m.invoke(service, r);
    }
}
