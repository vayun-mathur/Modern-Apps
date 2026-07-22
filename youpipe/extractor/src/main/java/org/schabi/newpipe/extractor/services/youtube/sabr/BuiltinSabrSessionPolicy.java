package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/** Bundled protocol behavior used when no verified JavaScript policy is active. */
public final class BuiltinSabrSessionPolicy implements SabrSessionPolicy {
    @Nonnull
    @Override
    public Result evaluate(@Nonnull final State state, @Nonnull final Event event) {
        if (event instanceof RequestEvent) {
            return Result.request(state, state.getRequestNumber() == 0
                            ? ActionType.SEND_INITIAL_REQUEST : ActionType.SEND_FOLLOW_UP_REQUEST,
                    ((RequestEvent) event).getProposedBody());
        }
        final ControlResponseEvent control = (ControlResponseEvent) event;
        final SabrDecodedResponse response = control.getResponse();
        final List<Action> actions = new ArrayList<>();
        State next = state;
        actions.add(new Action(ActionType.APPLY_RESPONSE_STATE));
        final String redirectUrl = response.getRedirectUrl();
        if (redirectUrl != null && !redirectUrl.isEmpty()) {
            actions.add(new Action(ActionType.APPLY_REDIRECT));
            next = new State(state.getRequestNumber(), state.getRedirectCount() + 1,
                    state.getPoTokenRefreshes(), state.getReloads());
        }
        if (response.getSabrErrorDetails() != null) {
            actions.add(new Action(ActionType.FAIL_SABR_ERROR));
            return Result.control(next, actions, new ControlDecision(0, redirectUrl,
                    response.getSabrErrorDetails().summarize()),
                    SabrResponseStatePatch.builtin(response));
        }
        if (response.isReloadRequested()) {
            actions.add(new Action(ActionType.TRY_RELOAD));
            return Result.control(next, actions, new ControlDecision(0, redirectUrl, null),
                    SabrResponseStatePatch.builtin(response));
        }
        if (response.isProtectionBoundaryNoMediaResponse()) {
            actions.add(new Action(control.getMode() == ControlMode.FETCH_SEGMENT
                    ? ActionType.REQUIRE_PO_TOKEN : ActionType.REFRESH_PO_TOKEN));
        }
        if (control.getMode() == ControlMode.PUMP && control.getSegmentCount() > 0) {
            next = state.resetRecoveryBudgets();
            actions.add(new Action(ActionType.RESET_RECOVERY_BUDGETS));
        }
        final int backoff = Math.max(0, response.getBackoffTimeMs());
        if (backoff > 0) {
            actions.add(new Action(control.shouldHonorBackoff()
                    ? ActionType.SLEEP_BACKOFF : ActionType.DEFER_BACKOFF));
        } else if (!control.shouldHonorBackoff()) {
            actions.add(new Action(ActionType.CLEAR_DEMAND_BACKOFF));
        }
        actions.add(new Action(control.getMode() == ControlMode.FETCH_SEGMENT
                && response.isProtectionBoundaryNoMediaResponse()
                ? ActionType.RETRY : ActionType.CONTINUE));
        return Result.control(next, actions, new ControlDecision(backoff, redirectUrl, null),
                SabrResponseStatePatch.builtin(response));
    }
}
