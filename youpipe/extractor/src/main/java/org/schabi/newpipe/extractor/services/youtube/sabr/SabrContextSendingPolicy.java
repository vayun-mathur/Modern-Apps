package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SabrContextSendingPolicy {
    private final List<Integer> startPolicy = new ArrayList<>();
    private final List<Integer> stopPolicy = new ArrayList<>();
    private final List<Integer> discardPolicy = new ArrayList<>();

    private SabrContextSendingPolicy() {
    }

    @Nonnull
    public static SabrContextSendingPolicy normalized(@Nonnull final List<Integer> start,
                                                       @Nonnull final List<Integer> stop,
                                                       @Nonnull final List<Integer> discard) {
        if (start.size() > 128 || stop.size() > 128 || discard.size() > 128
                || containsNegative(start) || containsNegative(stop) || containsNegative(discard)) {
            throw new IllegalArgumentException("Invalid normalized SABR context policy");
        }
        final SabrContextSendingPolicy policy = new SabrContextSendingPolicy();
        policy.startPolicy.addAll(start);
        policy.stopPolicy.addAll(stop);
        policy.discardPolicy.addAll(discard);
        return policy;
    }

    private static boolean containsNegative(@Nonnull final List<Integer> values) {
        for (final Integer value : values) {
            if (value == null || value < 0) return true;
        }
        return false;
    }

    @Nonnull
    static SabrContextSendingPolicy decode(@Nonnull final byte[] data)
            throws SabrProtocolException {
        final SabrContextSendingPolicy policy = new SabrContextSendingPolicy();
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            switch (field.getNumber()) {
                case 1:
                    policy.readPolicyValues(field, policy.startPolicy);
                    break;
                case 2:
                    policy.readPolicyValues(field, policy.stopPolicy);
                    break;
                case 3:
                    policy.readPolicyValues(field, policy.discardPolicy);
                    break;
                default:
                    break;
            }
        }
        return policy;
    }

    private void readPolicyValues(@Nonnull final SabrProto.Field field,
                                  @Nonnull final List<Integer> output)
            throws SabrProtocolException {
        if (field.getWireType() == SabrProto.WIRE_VARINT) {
            output.add((int) field.getVarint());
        } else if (field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
            for (final Long value : SabrProto.readPackedVarints(field.getBytes())) {
                output.add(value.intValue());
            }
        }
    }

    @Nonnull
    public List<Integer> getStartPolicy() {
        return Collections.unmodifiableList(startPolicy);
    }

    @Nonnull
    public List<Integer> getStopPolicy() {
        return Collections.unmodifiableList(stopPolicy);
    }

    @Nonnull
    public List<Integer> getDiscardPolicy() {
        return Collections.unmodifiableList(discardPolicy);
    }

    @Nonnull
    public String summarize() {
        return "start=" + startPolicy + ", stop=" + stopPolicy + ", discard=" + discardPolicy;
    }
}
