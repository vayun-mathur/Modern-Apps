package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Bounded, payload-free audit trail of policy decisions and applied Host actions. */
public final class SabrSessionPolicyTranscript {
    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public SabrSessionPolicyTranscript(final int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Invalid transcript capacity");
        this.capacity = capacity;
    }

    synchronized void record(@Nonnull final SabrSessionPolicy.State state,
                             @Nonnull final SabrSessionPolicy.Event event,
                             @Nonnull final SabrSessionPolicy.Result result) {
        if (entries.size() == capacity) entries.removeFirst();
        entries.addLast(new Entry(summary(state, event, result)));
    }

    synchronized void recordDemandRoute(
            @Nonnull final SabrSessionPolicy.DemandRouteEvent event,
            @Nonnull final SabrSessionPolicy.DemandRoute route) {
        append("v1 event=demand-route target=" + event.getTargetItag() + ':'
                + event.getTargetSequenceNumber() + " elapsedMs="
                + event.getState().getElapsedMs() + " omissions="
                + event.getState().getResponsesWithoutDemandedSegment() + " recoveries="
                + event.getState().getRecoveryCount() + " route=" + route);
    }

    synchronized void recordDemandResponse(
            @Nonnull final SabrSessionPolicy.DemandResponseEvent event,
            @Nonnull final SabrSessionPolicy.DemandResponseDecision decision) {
        append("v1 event=demand-response target=" + event.getTargetItag() + ':'
                + event.getTargetSequenceNumber() + " segments=" + event.getSegmentCount()
                + " targetTrack=" + event.getTargetTrackSegmentCount() + " returned="
                + event.getReturnedSegments().size() + " truncated="
                + event.areReturnedSegmentsTruncated() + " omissions="
                + event.getState().getResponsesWithoutDemandedSegment() + " outcome="
                + decision.getOutcome() + " retryDelayMs=" + decision.getRetryDelayMs());
    }

    private void append(@Nonnull final String summary) {
        if (entries.size() == capacity) entries.removeFirst();
        entries.addLast(new Entry(summary));
    }

    synchronized void commitLast(@Nonnull final SabrSessionPolicy.Result result,
                                 @Nonnull final SabrSessionPolicy.State appliedState,
                                 @Nonnull final List<SabrSessionPolicy.Action> actions,
                                 final boolean completed) {
        final List<SabrSessionPolicy.ActionType> types = new ArrayList<>();
        for (final SabrSessionPolicy.Action action : actions) types.add(action.getType());
        commitLastTypes(result, appliedState, types, completed);
    }

    synchronized void commitLastTypes(@Nonnull final SabrSessionPolicy.Result result,
                                      @Nonnull final SabrSessionPolicy.State appliedState,
                                      @Nonnull final List<SabrSessionPolicy.ActionType> actions,
                                      final boolean completed) {
        final Entry entry = entries.peekLast();
        if (entry != null) entry.commit = " applied=" + state(appliedState)
                + " executed=" + actions + " completed=" + completed;
    }

    @Nonnull
    public synchronized List<String> snapshot() {
        final List<String> result = new ArrayList<>();
        for (final Entry entry : entries) result.add(entry.decision + entry.commit);
        return Collections.unmodifiableList(result);
    }

    private static String summary(@Nonnull final SabrSessionPolicy.State state,
                                  @Nonnull final SabrSessionPolicy.Event event,
                                  @Nonnull final SabrSessionPolicy.Result result) {
        final String kind = event instanceof SabrSessionPolicy.RequestEvent ? "request" : "control";
        final int bytes = result.getRequestBody() == null ? 0 : result.getRequestBody().length;
        return "v1 state=" + state(state) + " event=" + kind + " actions="
                + result.getActions() + " requestBytes=" + bytes;
    }

    private static String state(@Nonnull final SabrSessionPolicy.State state) {
        return state.getRequestNumber() + "," + state.getRedirectCount() + ","
                + state.getPoTokenRefreshes() + "," + state.getReloads();
    }

    private static final class Entry {
        private final String decision;
        private String commit = "";
        private Entry(final String decision) { this.decision = decision; }
    }
}
