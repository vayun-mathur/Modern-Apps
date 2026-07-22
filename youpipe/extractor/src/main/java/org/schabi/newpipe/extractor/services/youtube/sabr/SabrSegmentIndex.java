package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SabrSegmentIndex {
    @Nonnull
    private final List<Entry> entries;

    SabrSegmentIndex(@Nonnull final List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    @Nullable
    public Entry getEntry(final int sequenceNumber) {
        if (sequenceNumber <= 0 || sequenceNumber > entries.size()) {
            return null;
        }
        return entries.get(sequenceNumber - 1);
    }

    public int size() {
        return entries.size();
    }

    public static final class Entry {
        private final int sequenceNumber;
        private final long startMs;
        private final long durationMs;

        Entry(final int sequenceNumber,
              final long startMs,
              final long durationMs) {
            this.sequenceNumber = sequenceNumber;
            this.startMs = startMs;
            this.durationMs = durationMs;
        }

        public int getSequenceNumber() {
            return sequenceNumber;
        }

        public long getStartMs() {
            return startMs;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public long getEndMs() {
            return startMs + durationMs;
        }
    }
}
