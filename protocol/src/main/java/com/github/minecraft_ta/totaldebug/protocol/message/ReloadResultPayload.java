package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-27 payload answering a {@link ReloadPayload}: how long the reload took, the warnings and errors it logged
 * about watched paths, and why it did not run in {@code error}, which is empty on success.
 */
public record ReloadResultPayload(int requestId, long millis, List<Problem> problems, String error) {
    public static final int MAX_PROBLEMS = 64;
    public static final int MAX_PROBLEM_LENGTH = 2_000;

    /** A logged warning or error, and the watched path it names. */
    public record Problem(String path, String message) {
        public Problem {
            Objects.requireNonNull(path, "path");
            message = message.length() > MAX_PROBLEM_LENGTH ? message.substring(0, MAX_PROBLEM_LENGTH) : message;
        }
    }

    public ReloadResultPayload {
        problems = problems.stream().limit(MAX_PROBLEMS).toList();
        Objects.requireNonNull(error, "error");
    }

    public static ReloadResultPayload read(ByteBufferInputStream input) {
        int requestId = input.readInt();
        long millis = input.readLong();
        int count = input.readInt();
        if (count < 0 || count > MAX_PROBLEMS) throw new IllegalArgumentException("Invalid problem count: " + count);
        List<Problem> problems = new ArrayList<>(count);
        for (int index = 0; index < count; index++) problems.add(new Problem(input.readString(), input.readString()));
        return new ReloadResultPayload(requestId, millis, problems, input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeLong(this.millis);
        output.writeInt(this.problems.size());
        for (Problem problem : this.problems) {
            output.writeString(problem.path());
            output.writeString(problem.message());
        }
        output.writeString(this.error);
    }
}
