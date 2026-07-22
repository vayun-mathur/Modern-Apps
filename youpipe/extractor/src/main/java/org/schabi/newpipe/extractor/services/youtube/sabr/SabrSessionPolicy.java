package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Policy contract between protocol code and the small set of capabilities owned by the Host.
 * Implementations receive bounded, data-only events and return request bytes or Host actions.
 */
public interface SabrSessionPolicy extends AutoCloseable {
    int MAX_DEMAND_RETURNED_SEGMENTS = 64;
    int MAX_DEMAND_RETRY_DELAY_MS = 5_000;

    enum ActionType {
        SEND_INITIAL_REQUEST,
        SEND_FOLLOW_UP_REQUEST,
        APPLY_BUILTIN_RESPONSE_STATE,
        APPLY_REDIRECT,
        FAIL_SABR_ERROR,
        TRY_RELOAD,
        REFRESH_PO_TOKEN,
        REQUIRE_PO_TOKEN,
        RESET_RECOVERY_BUDGETS,
        SLEEP_BACKOFF,
        DEFER_BACKOFF,
        CLEAR_DEMAND_BACKOFF,
        RETRY,
        CONTINUE,
        APPLY_RESPONSE_STATE
    }

    enum ControlMode { PUMP, FETCH_SEGMENT }

    enum DemandRoute {
        STREAM,
        REWIND,
        FORWARD,
        RECOVER_REWIND,
        RECOVER_FORWARD,
        RECOVER_MISSING
    }

    enum DemandOutcome {
        CONTINUE,
        FAIL_REPEATED_TARGET_OMISSION,
        FAIL_NO_TARGET_MEDIA
    }

    final class State {
        private final int requestNumber;
        private final int redirectCount;
        private final int poTokenRefreshes;
        private final int reloads;

        public State(final int requestNumber, final int redirectCount,
                     final int poTokenRefreshes, final int reloads) {
            this.requestNumber = requestNumber;
            this.redirectCount = redirectCount;
            this.poTokenRefreshes = poTokenRefreshes;
            this.reloads = reloads;
        }

        public int getRequestNumber() { return requestNumber; }
        public int getRedirectCount() { return redirectCount; }
        public int getPoTokenRefreshes() { return poTokenRefreshes; }
        public int getReloads() { return reloads; }

        @Nonnull
        public State resetRecoveryBudgets() {
            return new State(requestNumber, 0, 0, reloads);
        }

        @Override
        public boolean equals(final Object other) {
            return this == other || other instanceof State
                    && requestNumber == ((State) other).requestNumber
                    && redirectCount == ((State) other).redirectCount
                    && poTokenRefreshes == ((State) other).poTokenRefreshes
                    && reloads == ((State) other).reloads;
        }

        @Override
        public int hashCode() {
            return Objects.hash(requestNumber, redirectCount, poTokenRefreshes, reloads);
        }
    }

    abstract class Event { }

    /** Immutable Host-owned counters exposed to demand policy decisions. */
    final class DemandState {
        private final long createdAtMs;
        private final long nowMs;
        private final int responsesWithoutDemandedSegment;
        private final int recoveryCount;

        public DemandState(final long createdAtMs, final long nowMs,
                           final int responsesWithoutDemandedSegment,
                           final int recoveryCount) {
            this.createdAtMs = createdAtMs;
            this.nowMs = nowMs;
            this.responsesWithoutDemandedSegment = responsesWithoutDemandedSegment;
            this.recoveryCount = recoveryCount;
        }

        public long getCreatedAtMs() { return createdAtMs; }
        public long getNowMs() { return nowMs; }
        public long getElapsedMs() { return Math.max(0, nowMs - createdAtMs); }
        public int getResponsesWithoutDemandedSegment() {
            return responsesWithoutDemandedSegment;
        }
        public int getRecoveryCount() { return recoveryCount; }
    }

    abstract class DemandEvent {
        private final int targetItag;
        private final int targetSequenceNumber;
        private final long targetStartMs;
        private final long bufferedEdgeMs;
        @Nonnull private final DemandState state;

        DemandEvent(final int targetItag, final int targetSequenceNumber,
                    final long targetStartMs, final long bufferedEdgeMs,
                    @Nonnull final DemandState state) {
            this.targetItag = targetItag;
            this.targetSequenceNumber = targetSequenceNumber;
            this.targetStartMs = targetStartMs;
            this.bufferedEdgeMs = bufferedEdgeMs;
            this.state = Objects.requireNonNull(state);
        }

        public int getTargetItag() { return targetItag; }
        public int getTargetSequenceNumber() { return targetSequenceNumber; }
        public long getTargetStartMs() { return targetStartMs; }
        public long getBufferedEdgeMs() { return bufferedEdgeMs; }
        @Nonnull public DemandState getState() { return state; }
    }

    final class DemandRouteEvent extends DemandEvent {
        public DemandRouteEvent(final int targetItag, final int targetSequenceNumber,
                                final long targetStartMs, final long bufferedEdgeMs,
                                @Nonnull final DemandState state) {
            super(targetItag, targetSequenceNumber, targetStartMs, bufferedEdgeMs, state);
        }
    }

    /** Payload-free identity of one media segment returned while a reader demand was pending. */
    final class DemandReturnedSegment {
        private final int itag;
        private final int sequenceNumber;
        private final long startMs;
        private final long durationMs;

        public DemandReturnedSegment(final int itag, final int sequenceNumber,
                                     final long startMs, final long durationMs) {
            this.itag = itag;
            this.sequenceNumber = sequenceNumber;
            this.startMs = startMs;
            this.durationMs = durationMs;
        }

        public int getItag() { return itag; }
        public int getSequenceNumber() { return sequenceNumber; }
        public long getStartMs() { return startMs; }
        public long getDurationMs() { return durationMs; }
    }

    final class DemandResponseEvent extends DemandEvent {
        private final int segmentCount;
        private final int targetTrackSegmentCount;
        private final boolean returnedSegmentsTruncated;
        @Nonnull private final List<DemandReturnedSegment> returnedSegments;

        public DemandResponseEvent(final int targetItag, final int targetSequenceNumber,
                                   final long targetStartMs, final long bufferedEdgeMs,
                                   @Nonnull final DemandState state,
                                   final int segmentCount, final int targetTrackSegmentCount,
                                   @Nonnull final List<DemandReturnedSegment> returnedSegments,
                                   final boolean returnedSegmentsTruncated) {
            super(targetItag, targetSequenceNumber, targetStartMs, bufferedEdgeMs, state);
            this.segmentCount = segmentCount;
            this.targetTrackSegmentCount = targetTrackSegmentCount;
            this.returnedSegments = Collections.unmodifiableList(
                    new ArrayList<>(Objects.requireNonNull(returnedSegments)));
            this.returnedSegmentsTruncated = returnedSegmentsTruncated;
        }

        public int getSegmentCount() { return segmentCount; }
        public int getTargetTrackSegmentCount() { return targetTrackSegmentCount; }
        @Nonnull public List<DemandReturnedSegment> getReturnedSegments() {
            return returnedSegments;
        }
        public boolean areReturnedSegmentsTruncated() { return returnedSegmentsTruncated; }
    }

    final class DemandResponseDecision {
        @Nonnull private final DemandOutcome outcome;
        private final int retryDelayMs;

        public DemandResponseDecision(@Nonnull final DemandOutcome outcome,
                                      final int retryDelayMs) {
            this.outcome = Objects.requireNonNull(outcome);
            this.retryDelayMs = retryDelayMs;
        }

        @Nonnull public DemandOutcome getOutcome() { return outcome; }
        public int getRetryDelayMs() { return retryDelayMs; }
    }

    final class RequestEvent extends Event {
        private final long playerTimeMs;
        private final long bufferedEdgeMs;
        private final int poTokenBytes;
        private final int bufferedRangeCount;
        @Nonnull private final byte[] proposedBody;

        public RequestEvent(final long playerTimeMs, final long bufferedEdgeMs,
                            final int poTokenBytes, final int bufferedRangeCount,
                            @Nonnull final byte[] proposedBody) {
            this.playerTimeMs = playerTimeMs;
            this.bufferedEdgeMs = bufferedEdgeMs;
            this.poTokenBytes = poTokenBytes;
            this.bufferedRangeCount = bufferedRangeCount;
            this.proposedBody = proposedBody.clone();
        }

        public long getPlayerTimeMs() { return playerTimeMs; }
        public long getBufferedEdgeMs() { return bufferedEdgeMs; }
        public int getPoTokenBytes() { return poTokenBytes; }
        public int getBufferedRangeCount() { return bufferedRangeCount; }
        @Nonnull public byte[] getProposedBody() { return proposedBody.clone(); }
    }

    final class ControlResponseEvent extends Event {
        private final int segmentCount;
        private final boolean honorBackoff;
        @Nonnull private final ControlMode mode;
        @Nonnull private final SabrDecodedResponse response;

        public ControlResponseEvent(final int segmentCount, final boolean honorBackoff,
                                    @Nonnull final ControlMode mode,
                                    @Nonnull final SabrDecodedResponse response) {
            this.segmentCount = segmentCount;
            this.honorBackoff = honorBackoff;
            this.mode = Objects.requireNonNull(mode);
            this.response = Objects.requireNonNull(response);
        }

        public int getSegmentCount() { return segmentCount; }
        public boolean shouldHonorBackoff() { return honorBackoff; }
        @Nonnull public ControlMode getMode() { return mode; }
        @Nonnull public SabrDecodedResponse getResponse() { return response; }
    }

    final class Action {
        @Nonnull private final ActionType type;
        public Action(@Nonnull final ActionType type) { this.type = Objects.requireNonNull(type); }
        @Nonnull public ActionType getType() { return type; }
        @Override public boolean equals(final Object other) {
            return this == other || other instanceof Action && type == ((Action) other).type;
        }
        @Override public int hashCode() { return type.hashCode(); }
    }

    /** Values interpreted by Host capabilities; protocol parsing remains in the policy. */
    final class ControlDecision {
        private final int backoffTimeMs;
        @Nullable private final String redirectUrl;
        @Nullable private final String errorDetails;

        public ControlDecision(final int backoffTimeMs, @Nullable final String redirectUrl,
                               @Nullable final String errorDetails) {
            if (backoffTimeMs < 0) throw new IllegalArgumentException("Negative SABR backoff");
            this.backoffTimeMs = backoffTimeMs;
            this.redirectUrl = redirectUrl;
            this.errorDetails = errorDetails;
        }

        public int getBackoffTimeMs() { return backoffTimeMs; }
        @Nullable public String getRedirectUrl() { return redirectUrl; }
        @Nullable public String getErrorDetails() { return errorDetails; }
    }

    final class Result {
        @Nonnull private final State nextState;
        @Nonnull private final List<Action> actions;
        @Nullable private final byte[] requestBody;
        @Nullable private final ControlDecision controlDecision;
        @Nullable private final SabrResponseStatePatch statePatch;

        private Result(@Nonnull final State nextState, @Nonnull final List<Action> actions,
                       @Nullable final byte[] requestBody,
                       @Nullable final ControlDecision controlDecision,
                       @Nullable final SabrResponseStatePatch statePatch) {
            this.nextState = Objects.requireNonNull(nextState);
            this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
            this.requestBody = requestBody == null ? null : requestBody.clone();
            this.controlDecision = controlDecision;
            this.statePatch = statePatch;
        }

        @Nonnull
        public static Result request(@Nonnull final State state, @Nonnull final ActionType action,
                                     @Nonnull final byte[] body) {
            return new Result(state, Collections.singletonList(new Action(action)), body, null,
                    null);
        }

        @Nonnull
        public static Result control(@Nonnull final State state, @Nonnull final List<Action> actions,
                                     @Nonnull final ControlDecision decision) {
            return control(state, actions, decision, null);
        }

        @Nonnull
        public static Result control(@Nonnull final State state, @Nonnull final List<Action> actions,
                                     @Nonnull final ControlDecision decision,
                                     @Nullable final SabrResponseStatePatch statePatch) {
            return new Result(state, actions, null, decision, statePatch);
        }

        @Nonnull public State getNextState() { return nextState; }
        @Nonnull public List<Action> getActions() { return actions; }
        @Nullable public byte[] getRequestBody() {
            return requestBody == null ? null : requestBody.clone();
        }
        @Nullable public ControlDecision getControlDecision() { return controlDecision; }
        @Nullable public SabrResponseStatePatch getStatePatch() { return statePatch; }
    }

    /** Media framing is queried by the streaming Host without exposing media bytes to control JS. */
    @Nonnull
    default SabrMediaProtocol getMediaProtocol() { return SabrMediaProtocol.builtin(); }

    @Nonnull
    Result evaluate(@Nonnull State state, @Nonnull Event event) throws SabrProtocolException;

    /** Bundled demand routing; cloud policies may override this without owning the pump. */
    @Nonnull
    default DemandRoute evaluateDemandRoute(@Nonnull final DemandRouteEvent event)
            throws SabrProtocolException {
        final DemandState demand = event.getState();
        if (demand.getResponsesWithoutDemandedSegment() > demand.getRecoveryCount()) {
            if (event.getTargetStartMs() < event.getBufferedEdgeMs()) {
                return DemandRoute.RECOVER_REWIND;
            }
            if (event.getTargetStartMs() > event.getBufferedEdgeMs() + 30_000) {
                return DemandRoute.RECOVER_FORWARD;
            }
            return DemandRoute.RECOVER_MISSING;
        }
        if (event.getTargetStartMs() < event.getBufferedEdgeMs()) {
            return DemandRoute.REWIND;
        }
        if (event.getTargetStartMs() > event.getBufferedEdgeMs() + 30_000) {
            return DemandRoute.FORWARD;
        }
        return DemandRoute.STREAM;
    }

    /** Every event here is a media-bearing response that omitted the demanded itag/sequence. */
    @Nonnull
    default DemandResponseDecision evaluateDemandResponse(
            @Nonnull final DemandResponseEvent event) throws SabrProtocolException {
        final DemandState demand = event.getState();
        if (demand.getResponsesWithoutDemandedSegment() >= 3) {
            return new DemandResponseDecision(DemandOutcome.FAIL_REPEATED_TARGET_OMISSION, 0);
        }
        if (demand.getElapsedMs() >= 15_000) {
            return new DemandResponseDecision(event.getTargetTrackSegmentCount() > 0
                    ? DemandOutcome.FAIL_REPEATED_TARGET_OMISSION
                    : DemandOutcome.FAIL_NO_TARGET_MEDIA, 0);
        }
        return new DemandResponseDecision(DemandOutcome.CONTINUE, 0);
    }

    @Override
    default void close() { }
}
