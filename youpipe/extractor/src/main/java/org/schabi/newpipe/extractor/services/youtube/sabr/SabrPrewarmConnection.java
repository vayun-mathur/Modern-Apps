package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SabrPrewarmConnection {
    private static final int MAX_SUMMARY_ITEMS = 4;
    private static final int MAX_NESTING_DEPTH = 2;

    @Nonnull
    private final List<String> connections;
    @Nonnull
    private final List<String> extraFields;

    private SabrPrewarmConnection(@Nonnull final List<String> connections,
                                  @Nonnull final List<String> extraFields) {
        this.connections = Collections.unmodifiableList(new ArrayList<>(connections));
        this.extraFields = Collections.unmodifiableList(new ArrayList<>(extraFields));
    }

    @Nonnull
    static SabrPrewarmConnection decode(@Nonnull final byte[] data) throws SabrProtocolException {
        final List<String> connections = new ArrayList<>();
        final List<String> extraFields = new ArrayList<>();
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                connections.add(describeNestedMessage(field.getBytes(), 0));
            } else {
                extraFields.add(describeField(field, 0));
            }
        }
        return new SabrPrewarmConnection(connections, extraFields);
    }

    @Nonnull
    public List<String> getConnections() {
        return connections;
    }

    @Nonnull
    public List<String> getExtraFields() {
        return extraFields;
    }

    @Nonnull
    public String summarize() {
        return "connections=" + summarizeList(connections)
                + (extraFields.isEmpty() ? "" : ", extra=" + summarizeList(extraFields));
    }

    @Nonnull
    private static String summarizeList(@Nonnull final List<String> values) {
        final StringBuilder builder = new StringBuilder();
        builder.append(values.size()).append('[');
        final int sampleSize = Math.min(MAX_SUMMARY_ITEMS, values.size());
        for (int i = 0; i < sampleSize; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values.get(i));
        }
        if (values.size() > sampleSize) {
            builder.append(",...");
        }
        return builder.append(']').toString();
    }

    @Nonnull
    private static String describeNestedMessage(@Nonnull final byte[] data,
                                                final int depth) {
        if (depth >= MAX_NESTING_DEPTH) {
            return describeOpaqueBytes(data);
        }
        try {
            final List<String> fields = new ArrayList<>();
            for (final SabrProto.Field field : SabrProto.readFields(data)) {
                fields.add(describeField(field, depth + 1));
            }
            return '{' + join(fields) + '}';
        } catch (final SabrProtocolException e) {
            return describeOpaqueBytes(data);
        }
    }

    @Nonnull
    private static String describeOpaqueBytes(@Nonnull final byte[] data) {
        return "bytes(" + data.length + (isPrintableAscii(data) ? ",ascii" : "") + ')';
    }

    private static boolean isPrintableAscii(@Nonnull final byte[] data) {
        if (data.length == 0) {
            return false;
        }
        for (final byte value : data) {
            final int unsigned = value & 0xff;
            if (unsigned < 0x20 || unsigned > 0x7e) {
                return false;
            }
        }
        return true;
    }

    @Nonnull
    private static String describeField(@Nonnull final SabrProto.Field field,
                                        final int depth) throws SabrProtocolException {
        if (field.getWireType() == SabrProto.WIRE_VARINT) {
            return field.getNumber() + "=" + field.getVarint();
        }
        if (field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
            final byte[] bytes = field.getBytes();
            final String nested = describeNestedMessage(bytes, depth);
            return field.getNumber() + "=" + nested;
        }
        return field.getNumber() + "=bytes(" + field.getBytes().length + ')';
    }

    @Nonnull
    private static String join(@Nonnull final List<String> values) {
        final StringBuilder builder = new StringBuilder();
        for (final String value : values) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
