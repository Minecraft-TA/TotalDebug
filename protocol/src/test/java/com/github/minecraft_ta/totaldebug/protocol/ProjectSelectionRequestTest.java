package com.github.minecraft_ta.totaldebug.protocol;

import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ProjectSelectionRequestTest {
    @Test void reusesTheBoundedHelloPayloadAndRejectsTrailingData() {
        var original = new ClientHelloMessage(CompanionProtocol.VERSION, "secret", "project", "data", "game");
        var bytes = ProjectSelectionRequest.encode(original);
        var decoded = ProjectSelectionRequest.decode(bytes);
        assertEquals(original.profileId(), decoded.profileId());
        assertEquals(original.token(), decoded.token());
        assertEquals(original.dataDirectory(), decoded.dataDirectory());
        assertEquals(original.workspaceDirectory(), decoded.workspaceDirectory());
        assertThrows(IllegalArgumentException.class, () -> ProjectSelectionRequest.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> ProjectSelectionRequest.decode(new byte[ProjectSelectionRequest.MAX_BYTES + 1]));
    }
}
