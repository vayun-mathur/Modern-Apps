package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SabrRequestCancellationPolicy {
    private final int field1;
    private final int field3;
    @Nonnull
    private final List<Item> items;

    private SabrRequestCancellationPolicy(final int field1,
                                          final int field3,
                                          @Nonnull final List<Item> items) {
        this.field1 = field1;
        this.field3 = field3;
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    @Nonnull
    static SabrRequestCancellationPolicy decode(@Nonnull final byte[] data)
            throws SabrProtocolException {
        int field1 = 0;
        int field3 = 0;
        final List<Item> items = new ArrayList<>();
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                field1 = (int) field.getVarint();
            } else if (field.getNumber() == 2
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                items.add(Item.decode(field.getBytes()));
            } else if (field.getNumber() == 3
                    && field.getWireType() == SabrProto.WIRE_VARINT) {
                field3 = (int) field.getVarint();
            }
        }
        return new SabrRequestCancellationPolicy(field1, field3, items);
    }

    public int getField1() {
        return field1;
    }

    public int getField3() {
        return field3;
    }

    @Nonnull
    public List<Item> getItems() {
        return items;
    }

    @Nonnull
    public String summarize() {
        final StringBuilder builder = new StringBuilder();
        builder.append("field1=").append(field1)
                .append(", items=").append(items.size()).append('[');
        final int sampleSize = Math.min(4, items.size());
        for (int i = 0; i < sampleSize; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(items.get(i).summarize());
        }
        if (items.size() > sampleSize) {
            builder.append(",...");
        }
        builder.append(']').append(", field3=").append(field3);
        return builder.toString();
    }

    public static final class Item {
        private final int field1;
        private final int field2;
        private final int minReadaheadMs;

        private Item(final int field1,
                     final int field2,
                     final int minReadaheadMs) {
            this.field1 = field1;
            this.field2 = field2;
            this.minReadaheadMs = minReadaheadMs;
        }

        @Nonnull
        private static Item decode(@Nonnull final byte[] data) throws SabrProtocolException {
            int field1 = 0;
            int field2 = 0;
            int minReadaheadMs = 0;
            for (final SabrProto.Field field : SabrProto.readFields(data)) {
                if (field.getWireType() != SabrProto.WIRE_VARINT) {
                    continue;
                }
                if (field.getNumber() == 1) {
                    field1 = (int) field.getVarint();
                } else if (field.getNumber() == 2) {
                    field2 = (int) field.getVarint();
                } else if (field.getNumber() == 3) {
                    minReadaheadMs = (int) field.getVarint();
                }
            }
            return new Item(field1, field2, minReadaheadMs);
        }

        public int getField1() {
            return field1;
        }

        public int getField2() {
            return field2;
        }

        public int getMinReadaheadMs() {
            return minReadaheadMs;
        }

        @Nonnull
        public String summarize() {
            return "field1=" + field1 + "/field2=" + field2
                    + "/minReadaheadMs=" + minReadaheadMs;
        }
    }
}
