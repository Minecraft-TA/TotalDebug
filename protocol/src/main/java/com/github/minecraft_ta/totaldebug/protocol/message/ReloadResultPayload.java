package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-27 payload answering a {@link ReloadPayload}: how long the reload took, the warnings and errors it logged
 * that name a watched path, and why it did not run in {@code error}, which is empty on success.
 */
public record ReloadResultPayload(int requestId, long millis, List<String> problems, String error) {
    public static final int MAX_PROBLEMS = 64;
    public static final int MAX_PROBLEM_LENGTH = 2_000;

    public ReloadResultPayload {
        problems = problems.stream()
                .limit(MAX_PROBLEMS)
                .map(problem -> problem.length() > MAX_PROBLEM_LENGTH ? problem.substring(0, MAX_PROBLEM_LENGTH) : problem)
                .toList();
        Objects.requireNonNull(error, "error");
    }

    public static ReloadResultPayload read(ByteBufferInputStream input) {
        int requestId = input.readInt();
        long millis = input.readLong();
        int count = input.readInt();
        if (count < 0 || count > MAX_PROBLEMS) throw new IllegalArgumentException("Invalid problem count: " + count);
        List<String> problems = new ArrayList<>(count);
        for (int index = 0; index < count; index++) problems.add(input.readString());
        return new ReloadResultPayload(requestId, millis, problems, input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeLong(this.millis);
        output.writeInt(this.problems.size());
        for (String problem : this.problems) output.writeString(problem);
        output.writeString(this.error);
    }
}
