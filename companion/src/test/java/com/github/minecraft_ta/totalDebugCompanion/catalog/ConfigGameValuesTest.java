package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.ConfigValueResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetConfigValuePayload;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigGameValuesTest {
    private static final ChangeRecord.Setting SPEED = new ChangeRecord.Setting("testmod", "testmod-common.toml",
            Path.of("config/testmod-common.toml"), "widgets.speed");

    @Test
    void aTriedValueIsRecordedAtTheGameLevelUntilItIsReverted() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ConfigGameValues values = new ConfigGameValues(record);
        List<SetConfigValuePayload> sent = new ArrayList<>();
        values.gameConnected(message -> {
            SetConfigValuePayload request = message.payload();
            sent.add(request);
            values.answered(new ConfigValueResultPayload(request.requestId(), "9", request.literal(), ""));
            return true;
        });

        assertEquals("12", values.set(SPEED, "9", "12").get(5, TimeUnit.SECONDS));
        assertEquals(new SetConfigValuePayload(1, "testmod-common.toml", "widgets.speed", "12"), sent.getFirst());
        assertEquals("12", values.tried(SPEED));
        assertEquals("9", record.change(SPEED, ChangeRecord.Level.GAME).original());

        values.revert(record.change(SPEED, ChangeRecord.Level.GAME)).get(5, TimeUnit.SECONDS);
        assertEquals("9", sent.get(1).literal(), "reverting sets the file's value again");
        assertNull(values.tried(SPEED));
        assertEquals(0, record.size());
    }

    @Test
    void aLateAnswerIsStillRecorded() {
        ChangeRecord record = ChangeRecord.inMemory();
        ConfigGameValues values = new ConfigGameValues(record);
        List<SetConfigValuePayload> sent = new ArrayList<>();
        values.gameConnected(message -> sent.add(message.payload()));

        values.set(SPEED, "9", "12");
        assertNull(values.tried(SPEED));
        values.answered(new ConfigValueResultPayload(sent.getFirst().requestId(), "9", "12", ""));
        assertEquals("12", values.tried(SPEED), "the game applied the value, whether or not the caller still waits");
    }

    @Test
    void aRefusedValueIsNotRecorded() {
        ChangeRecord record = ChangeRecord.inMemory();
        ConfigGameValues values = new ConfigGameValues(record);
        values.gameConnected(message -> {
            values.answered(new ConfigValueResultPayload(message.payload().requestId(), "", "", "13 is not allowed for widgets.speed"));
            return true;
        });

        Throwable failure = values.set(SPEED, "9", "13").handle((ignored, thrown) -> thrown).join();
        assertTrue(failure.getCause().getMessage().contains("not allowed"), failure.getCause().getMessage());
        assertEquals(0, record.size());
    }
}
