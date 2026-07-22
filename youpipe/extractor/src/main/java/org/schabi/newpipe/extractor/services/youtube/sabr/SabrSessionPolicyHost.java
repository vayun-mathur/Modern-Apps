package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates policy output before executing any network, token, timing, or media capability. */
public final class SabrSessionPolicyHost implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 256 * 1024;
    private static final Set<SabrSessionPolicy.ActionType> TERMINAL = EnumSet.of(
            SabrSessionPolicy.ActionType.CONTINUE, SabrSessionPolicy.ActionType.RETRY,
            SabrSessionPolicy.ActionType.FAIL_SABR_ERROR,
            SabrSessionPolicy.ActionType.TRY_RELOAD);
    @Nonnull private final SabrSessionPolicy policy;
    @Nullable private final SabrSessionPolicyTranscript transcript;

    public SabrSessionPolicyHost(@Nonnull final SabrSessionPolicy policy,
                                 @Nullable final SabrSessionPolicyTranscript transcript) {
        this.policy = Objects.requireNonNull(policy);
        this.transcript = transcript;
    }

    @Nonnull
    public SabrSessionPolicy.Result evaluate(@Nonnull final SabrSessionPolicy.State state,
                                             @Nonnull final SabrSessionPolicy.Event event)
            throws SabrProtocolException {
        validateState(state);
        final SabrSessionPolicy.Result result = policy.evaluate(state, event);
        validateResult(state, event, result);
        if (transcript != null) transcript.record(state, event, result);
        return result;
    }

    @Nonnull public SabrMediaProtocol getMediaProtocol() { return policy.getMediaProtocol(); }

    @Nonnull
    public SabrSessionPolicy.DemandRoute evaluateDemandRoute(
            @Nonnull final SabrSessionPolicy.DemandRouteEvent event)
            throws SabrProtocolException {
        validateDemandEvent(event);
        final SabrSessionPolicy.DemandRoute route = policy.evaluateDemandRoute(event);
        if (route == null) {
            throw new IllegalStateException("SABR demand policy returned no route");
        }
        if (transcript != null) transcript.recordDemandRoute(event, route);
        return route;
    }

    @Nonnull
    public SabrSessionPolicy.DemandResponseDecision evaluateDemandResponse(
            @Nonnull final SabrSessionPolicy.DemandResponseEvent event)
            throws SabrProtocolException {
        validateDemandEvent(event);
        if (event.getSegmentCount() <= 0 || event.getTargetTrackSegmentCount() < 0
                || event.getTargetTrackSegmentCount() > event.getSegmentCount()
                || event.getReturnedSegments().size()
                > SabrSessionPolicy.MAX_DEMAND_RETURNED_SEGMENTS
                || !event.areReturnedSegmentsTruncated()
                && event.getReturnedSegments().size() > event.getSegmentCount()) {
            throw new IllegalArgumentException("Invalid SABR demand response event");
        }
        for (final SabrSessionPolicy.DemandReturnedSegment segment
                : event.getReturnedSegments()) {
            if (segment == null || segment.getItag() <= 0 || segment.getSequenceNumber() < 0
                    || segment.getStartMs() < 0 || segment.getDurationMs() < 0) {
                throw new IllegalArgumentException("Invalid SABR returned segment identity");
            }
        }
        final SabrSessionPolicy.DemandResponseDecision decision =
                policy.evaluateDemandResponse(event);
        if (decision == null || decision.getOutcome() == null
                || decision.getRetryDelayMs() < 0
                || decision.getRetryDelayMs() > SabrSessionPolicy.MAX_DEMAND_RETRY_DELAY_MS) {
            throw new IllegalStateException("Invalid SABR demand response decision");
        }
        if (decision.getOutcome() != SabrSessionPolicy.DemandOutcome.CONTINUE
                && decision.getRetryDelayMs() != 0) {
            throw new IllegalStateException("Terminal SABR demand decision requested retry delay");
        }
        if (transcript != null) transcript.recordDemandResponse(event, decision);
        return decision;
    }
    @Nonnull public List<String> snapshotTranscript() {
        return transcript == null ? Collections.emptyList() : transcript.snapshot();
    }

    public void commitAppliedState(@Nonnull final SabrSessionPolicy.Result result,
                                   @Nonnull final SabrSessionPolicy.State state) {
        if (transcript != null) transcript.commitLast(result, state, result.getActions(), true);
    }

    public void commitAppliedState(@Nonnull final SabrSessionPolicy.Result result,
                                   @Nonnull final SabrSessionPolicy.State state,
                                   @Nonnull final List<SabrSessionPolicy.ActionType> actions,
                                   final boolean completed) {
        if (transcript != null) transcript.commitLastTypes(result, state, actions, completed);
    }

    private static void validateState(@Nonnull final SabrSessionPolicy.State state) {
        if (state.getRequestNumber() < 0 || state.getRedirectCount() < 0
                || state.getPoTokenRefreshes() < 0 || state.getReloads() < 0) {
            throw new IllegalStateException("Invalid SABR policy state");
        }
    }

    private static void validateDemandEvent(@Nonnull final SabrSessionPolicy.DemandEvent event) {
        final SabrSessionPolicy.DemandState state = event.getState();
        if (event.getTargetItag() <= 0 || event.getTargetSequenceNumber() < 0
                || event.getTargetStartMs() < 0 || event.getBufferedEdgeMs() < 0
                || state.getCreatedAtMs() < 0 || state.getNowMs() < state.getCreatedAtMs()
                || state.getResponsesWithoutDemandedSegment() < 0
                || state.getRecoveryCount() < 0
                || state.getRecoveryCount()
                > state.getResponsesWithoutDemandedSegment()) {
            throw new IllegalArgumentException("Invalid SABR demand policy event");
        }
    }

    private static void validateResult(@Nonnull final SabrSessionPolicy.State state,
                                       @Nonnull final SabrSessionPolicy.Event event,
                                       @Nullable final SabrSessionPolicy.Result result) {
        if (result == null || result.getActions().isEmpty()) {
            throw new IllegalStateException("SABR policy returned no result");
        }
        validateState(result.getNextState());
        if (event instanceof SabrSessionPolicy.RequestEvent) {
            final SabrSessionPolicy.ActionType expected = state.getRequestNumber() == 0
                    ? SabrSessionPolicy.ActionType.SEND_INITIAL_REQUEST
                    : SabrSessionPolicy.ActionType.SEND_FOLLOW_UP_REQUEST;
            if (result.getActions().size() != 1
                    || result.getActions().get(0).getType() != expected
                    || result.getRequestBody() == null || result.getRequestBody().length == 0
                    || result.getRequestBody().length > MAX_REQUEST_BYTES
                    || result.getControlDecision() != null || !state.equals(result.getNextState())) {
                throw new IllegalStateException("Invalid SABR request policy result");
            }
            return;
        }
        if (result.getRequestBody() != null || result.getControlDecision() == null) {
            throw new IllegalStateException("Invalid SABR control policy result");
        }
        final List<SabrSessionPolicy.Action> actions = result.getActions();
        final Set<SabrSessionPolicy.ActionType> seen = EnumSet.noneOf(
                SabrSessionPolicy.ActionType.class);
        for (final SabrSessionPolicy.Action action : actions) {
            if (action == null || !seen.add(action.getType())) {
                throw new IllegalStateException("Duplicate SABR control action");
            }
        }
        int terminalCount = 0;
        for (final SabrSessionPolicy.Action action : actions) {
            if (TERMINAL.contains(action.getType())) terminalCount++;
        }
        if (terminalCount != 1 || !TERMINAL.contains(actions.get(actions.size() - 1).getType())) {
            throw new IllegalStateException("SABR control policy has no terminal action");
        }
        final SabrSessionPolicy.ControlDecision decision = result.getControlDecision();
        if (seen.contains(SabrSessionPolicy.ActionType.APPLY_RESPONSE_STATE)
                != (result.getStatePatch() != null)) {
            throw new IllegalStateException("SABR response state action/patch mismatch");
        }
        if (seen.contains(SabrSessionPolicy.ActionType.APPLY_RESPONSE_STATE)
                && seen.contains(SabrSessionPolicy.ActionType.APPLY_BUILTIN_RESPONSE_STATE)) {
            throw new IllegalStateException("SABR response state actions are mutually exclusive");
        }
        if (seen.contains(SabrSessionPolicy.ActionType.APPLY_REDIRECT)
                != (decision.getRedirectUrl() != null && !decision.getRedirectUrl().isEmpty())) {
            throw new IllegalStateException("SABR redirect action/value mismatch");
        }
        final SabrSessionPolicy.ControlResponseEvent control =
                (SabrSessionPolicy.ControlResponseEvent) event;
        final boolean reset = seen.contains(SabrSessionPolicy.ActionType.RESET_RECOVERY_BUDGETS);
        final boolean redirect = seen.contains(SabrSessionPolicy.ActionType.APPLY_REDIRECT);
        final int expectedRedirects = reset ? 0
                : state.getRedirectCount() + (redirect ? 1 : 0);
        final int expectedRefreshes = reset ? 0 : state.getPoTokenRefreshes();
        final SabrSessionPolicy.State next = result.getNextState();
        if (next.getRequestNumber() != state.getRequestNumber()
                || next.getReloads() != state.getReloads()
                || next.getRedirectCount() != expectedRedirects
                || next.getPoTokenRefreshes() != expectedRefreshes
                || reset && (control.getMode() != SabrSessionPolicy.ControlMode.PUMP
                || control.getSegmentCount() <= 0)) {
            throw new IllegalStateException("Invalid SABR recovery state transition");
        }
        if (seen.contains(SabrSessionPolicy.ActionType.SLEEP_BACKOFF)
                != (decision.getBackoffTimeMs() > 0 && control.shouldHonorBackoff())
                || seen.contains(SabrSessionPolicy.ActionType.DEFER_BACKOFF)
                != (decision.getBackoffTimeMs() > 0 && !control.shouldHonorBackoff())
                || seen.contains(SabrSessionPolicy.ActionType.CLEAR_DEMAND_BACKOFF)
                && (decision.getBackoffTimeMs() > 0 || control.shouldHonorBackoff())
                || seen.contains(SabrSessionPolicy.ActionType.REQUIRE_PO_TOKEN)
                && control.getMode() != SabrSessionPolicy.ControlMode.FETCH_SEGMENT
                || seen.contains(SabrSessionPolicy.ActionType.REFRESH_PO_TOKEN)
                && control.getMode() != SabrSessionPolicy.ControlMode.PUMP) {
            throw new IllegalStateException("SABR Host action/event mismatch");
        }
    }

    @Override public void close() { policy.close(); }
}
