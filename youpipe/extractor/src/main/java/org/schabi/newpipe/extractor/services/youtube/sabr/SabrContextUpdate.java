package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrContextUpdate {
    static final int WRITE_POLICY_OVERWRITE = 1;
    static final int WRITE_POLICY_KEEP_EXISTING = 2;

    private final int type;
    private final int scope;
    @Nonnull
    private final byte[] value;
    private final boolean sendByDefault;
    private final int writePolicy;
    @Nullable
    private final SabrContextValue decodedValue;

    private SabrContextUpdate(final int type,
                               final int scope,
                               @Nonnull final byte[] value,
                               final boolean sendByDefault,
                               final int writePolicy,
                               @Nullable final SabrContextValue decodedValue) {
        this.type = type;
        this.scope = scope;
        this.value = value.clone();
        this.sendByDefault = sendByDefault;
        this.writePolicy = writePolicy;
        this.decodedValue = decodedValue;
    }

    @Nonnull
    public static SabrContextUpdate normalized(final int type, final int scope,
                                                @Nonnull final byte[] value,
                                                final boolean sendByDefault,
                                                final int writePolicy) {
        if (type < 0 || value.length == 0 || value.length > 64 * 1024) {
            throw new IllegalArgumentException("Invalid normalized SABR context update");
        }
        return new SabrContextUpdate(type, scope, value, sendByDefault, writePolicy, null);
    }

    @Nonnull
    static SabrContextUpdate decode(@Nonnull final byte[] data) throws SabrProtocolException {
        int type = -1;
        int scope = -1;
        byte[] value = new byte[0];
        boolean sendByDefault = false;
        int writePolicy = -1;
        SabrContextValue decodedValue = null;

        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            switch (field.getNumber()) {
                case 1:
                    type = (int) field.getVarint();
                    break;
                case 2:
                    scope = (int) field.getVarint();
                    break;
                case 3:
                    value = field.getBytes();
                    try {
                        decodedValue = SabrContextValue.decode(value);
                    } catch (final SabrProtocolException ignored) {
                        decodedValue = null;
                    }
                    break;
                case 4:
                    sendByDefault = field.getVarint() != 0;
                    break;
                case 5:
                    writePolicy = (int) field.getVarint();
                    break;
                default:
                    break;
            }
        }

        return new SabrContextUpdate(type, scope, value, sendByDefault, writePolicy,
                decodedValue);
    }

    @Nonnull
    byte[] toStreamerContextProto() {
        final SabrProto.Writer context = new SabrProto.Writer();
        context.writeInt32(1, type);
        context.writeBytes(2, value);
        return context.toByteArray();
    }

    public int getType() {
        return type;
    }

    public int getScope() {
        return scope;
    }

    @Nonnull
    public byte[] getValue() {
        return value.clone();
    }

    int getValueLength() {
        return value.length;
    }

    public boolean isSendByDefault() {
        return sendByDefault;
    }

    public int getWritePolicy() {
        return writePolicy;
    }

    @Nullable
    public SabrContextValue getDecodedValue() {
        return decodedValue;
    }

    @Nonnull
    public String summarize() {
        return "type=" + type
                + ", scope=" + scope
                + ", valueBytes=" + value.length
                + ", sendByDefault=" + sendByDefault
                + ", writePolicy=" + writePolicy
                + ", value=" + (decodedValue == null ? "undecoded" : decodedValue.summarize());
    }
}
