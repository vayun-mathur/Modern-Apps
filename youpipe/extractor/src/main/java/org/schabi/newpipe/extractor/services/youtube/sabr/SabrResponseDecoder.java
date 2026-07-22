package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.util.List;

public final class SabrResponseDecoder {
    public static final int ONESIE_HEADER = 10;
    public static final int ONESIE_DATA = 11;
    public static final int ONESIE_ENCRYPTED_MEDIA = 12;
    public static final int MEDIA_HEADER = 20;
    public static final int MEDIA = 21;
    public static final int MEDIA_END = 22;
    public static final int CONFIG = 30;
    public static final int LIVE_METADATA = 31;
    public static final int HOSTNAME_CHANGE_HINT_DEPRECATED = 32;
    public static final int LIVE_METADATA_PROMISE = 33;
    public static final int LIVE_METADATA_PROMISE_CANCELLATION = 34;
    public static final int NEXT_REQUEST_POLICY = 35;
    public static final int USTREAMER_VIDEO_AND_FORMAT_METADATA = 36;
    public static final int FORMAT_SELECTION_CONFIG = 37;
    public static final int USTREAMER_SELECTED_MEDIA_STREAM = 38;
    public static final int FORMAT_INITIALIZATION_METADATA = 42;
    public static final int SABR_REDIRECT = 43;
    public static final int SABR_ERROR = 44;
    public static final int SABR_SEEK = 45;
    public static final int RELOAD_PLAYER_RESPONSE = 46;
    public static final int PLAYBACK_START_POLICY = 47;
    public static final int ALLOWED_CACHED_FORMATS = 48;
    public static final int START_BW_SAMPLING_HINT = 49;
    public static final int PAUSE_BW_SAMPLING_HINT = 50;
    public static final int SELECTABLE_FORMATS = 51;
    public static final int REQUEST_IDENTIFIER = 52;
    public static final int REQUEST_CANCELLATION_POLICY = 53;
    public static final int ONESIE_PREFETCH_REJECTION = 54;
    public static final int TIMELINE_CONTEXT = 55;
    public static final int REQUEST_PIPELINING = 56;
    public static final int SABR_CONTEXT_UPDATE = 57;
    public static final int STREAM_PROTECTION_STATUS = 58;
    public static final int SABR_CONTEXT_SENDING_POLICY = 59;
    public static final int LAWNMOWER_POLICY = 60;
    public static final int SABR_ACK = 61;
    public static final int END_OF_TRACK = 62;
    public static final int CACHE_LOAD_POLICY = 63;
    public static final int LAWNMOWER_MESSAGING_POLICY = 64;
    public static final int PREWARM_CONNECTION = 65;
    public static final int PLAYBACK_DEBUG_INFO = 66;
    public static final int SNACKBAR_MESSAGE = 67;

    private SabrResponseDecoder() {
    }

    @Nonnull
    public static SabrDecodedResponse decode(@Nonnull final byte[] data)
            throws SabrProtocolException {
        return decodeParts(UmpReader.readAll(data));
    }

    /**
     * Decode an already-parsed list of UMP parts. Used by the streaming path, which collects the
     * small control parts (everything except the big MEDIA payloads) and decodes them here, while
     * the MEDIA segments are assembled separately so the whole body is never held at once.
     */
    @Nonnull
    public static SabrDecodedResponse decodeParts(@Nonnull final List<UmpPart> parts)
            throws SabrProtocolException {
        return decodeParts(parts, SabrMediaProtocol.builtin());
    }

    @Nonnull
    static SabrDecodedResponse decodeParts(@Nonnull final List<UmpPart> parts,
                                           @Nonnull final SabrMediaProtocol mediaProtocol)
            throws SabrProtocolException {
        final SabrDecodedResponse decoded = new SabrDecodedResponse();
        SabrOnesieHeader currentOnesieHeader = null;
        for (final UmpPart part : parts) {
            final byte[] partData = part.getRawData();
            decoded.addPart(part);
            if (part.getType() != mediaProtocol.getMediaPartType()
                    && part.getType() != mediaProtocol.getEndPartType()) {
                try {
                    decoded.addWireFieldSummary(part.getType(),
                            SabrProto.summarizeFields(partData));
                } catch (final SabrProtocolException ignored) {
                    decoded.addWireFieldSummary(part.getType(),
                            "opaqueBytes=" + partData.length);
                }
            }
            try {
                if (part.getType() == mediaProtocol.getHeaderPartType()) {
                    decoded.addMediaHeader(mediaProtocol.decodeHeader(partData));
                    continue;
                }
                if (part.getType() == mediaProtocol.getMediaPartType()) {
                    if (partData.length > 0) {
                        decoded.addMediaBytes(partData[0] & 0xff, partData.length - 1L);
                    }
                    continue;
                }
                if (part.getType() == mediaProtocol.getEndPartType()) {
                    if (partData.length > 0) decoded.addMediaEndHeaderId(partData[0] & 0xff);
                    continue;
                }
                switch (part.getType()) {
                case ONESIE_HEADER:
                    final SabrOnesieHeader onesieHeader =
                            SabrOnesieHeader.decode(partData);
                    currentOnesieHeader = onesieHeader;
                    decoded.addOnesieHeader(onesieHeader);
                    decoded.addGenericPartDescription(part.getType(), onesieHeader.summarize());
                    break;
                case ONESIE_DATA:
                case ONESIE_ENCRYPTED_MEDIA:
                    final SabrOnesieData onesieData = SabrOnesieData.fromPart(partData,
                            part.getType() == ONESIE_ENCRYPTED_MEDIA, currentOnesieHeader);
                    decoded.addOnesieData(onesieData);
                    decoded.addGenericPartDescription(part.getType(), onesieData.summarize());
                    break;
                case FORMAT_INITIALIZATION_METADATA:
                    final SabrFormatInitializationMetadata metadata =
                            SabrFormatInitializationMetadata.decode(partData);
                    decoded.addFormatInitializationMetadata(metadata);
                    decoded.addGenericPartDescription(part.getType(), metadata.summarize());
                    break;
                case MEDIA_HEADER:
                    decoded.addMediaHeader(SabrMediaHeader.decode(partData));
                    break;
                case MEDIA:
                    if (partData.length > 0) {
                        decoded.addMediaBytes(partData[0] & 0xff, partData.length - 1L);
                    }
                    break;
                case MEDIA_END:
                    if (partData.length > 0) {
                        decoded.addMediaEndHeaderId(partData[0] & 0xff);
                    }
                    break;
                case LIVE_METADATA:
                    final SabrLiveMetadata liveMetadata = SabrLiveMetadata.decode(partData);
                    decoded.addLiveMetadata(liveMetadata);
                    decoded.addGenericPartDescription(part.getType(), liveMetadata.summarize());
                    break;
                case NEXT_REQUEST_POLICY:
                    final SabrNextRequestPolicy nextRequestPolicy =
                            decodeNextRequestPolicy(partData, decoded);
                    decoded.addGenericPartDescription(part.getType(),
                            nextRequestPolicy.summarize());
                    break;
                case SABR_REDIRECT:
                    final SabrRedirect redirect = SabrRedirect.decode(partData);
                    decoded.setRedirect(redirect);
                    decoded.setRedirectUrl(redirect.getUrl());
                    decoded.addGenericPartDescription(part.getType(), redirect.summarize());
                    break;
                case SABR_SEEK:
                    final SabrSeek sabrSeek = SabrSeek.decode(partData);
                    decoded.setSabrSeek(sabrSeek);
                    decoded.addGenericPartDescription(part.getType(), sabrSeek.summarize());
                    break;
                case SABR_ERROR:
                    final SabrError sabrError = SabrError.decode(partData);
                    decoded.setSabrErrorDetails(sabrError);
                    decoded.setSabrError(sabrError.summarize());
                    decoded.addGenericPartDescription(part.getType(), sabrError.summarize());
                    break;
                case RELOAD_PLAYER_RESPONSE:
                    final SabrReloadPlayerResponse reloadPlayerResponse =
                            SabrReloadPlayerResponse.decode(partData);
                    decoded.setReloadRequested(true);
                    decoded.setReloadPlayerResponse(reloadPlayerResponse);
                    decoded.addGenericPartDescription(part.getType(),
                            reloadPlayerResponse.summarize());
                    break;
                case STREAM_PROTECTION_STATUS:
                    final SabrStreamProtectionStatus streamProtection =
                            SabrStreamProtectionStatus.decode(partData);
                    decoded.setStreamProtection(streamProtection);
                    decoded.setStreamProtectionStatus(streamProtection.getStatus());
                    decoded.setStreamProtectionMaxRetries(streamProtection.getMaxRetries());
                    decoded.addGenericPartDescription(part.getType(),
                            streamProtection.summarize());
                    break;
                case PLAYBACK_START_POLICY:
                    final SabrPlaybackStartPolicy playbackStartPolicy =
                            SabrPlaybackStartPolicy.decode(partData);
                    decoded.setPlaybackStartPolicy(playbackStartPolicy);
                    decoded.addGenericPartDescription(part.getType(),
                            playbackStartPolicy.summarize());
                    break;
                case SABR_CONTEXT_UPDATE:
                    final SabrContextUpdate sabrContextUpdate =
                            SabrContextUpdate.decode(partData);
                    decoded.addSabrContextUpdate(sabrContextUpdate);
                    decoded.addGenericPartDescription(part.getType(),
                            sabrContextUpdate.summarize());
                    break;
                case SABR_CONTEXT_SENDING_POLICY:
                    final SabrContextSendingPolicy sabrContextSendingPolicy =
                            SabrContextSendingPolicy.decode(partData);
                    decoded.setSabrContextSendingPolicy(sabrContextSendingPolicy);
                    decoded.addGenericPartDescription(part.getType(),
                            sabrContextSendingPolicy.summarize());
                    break;
                case SNACKBAR_MESSAGE:
                    final SabrSnackbarMessage snackbarMessage =
                            SabrSnackbarMessage.decode(partData);
                    decoded.setSnackbarMessage(snackbarMessage);
                    decoded.addGenericPartDescription(part.getType(), snackbarMessage.summarize());
                    break;
                case FORMAT_SELECTION_CONFIG:
                    final SabrFormatSelectionConfig formatSelectionConfig =
                            SabrFormatSelectionConfig.decode(partData);
                    decoded.setFormatSelectionConfig(formatSelectionConfig);
                    decoded.addGenericPartDescription(part.getType(),
                            formatSelectionConfig.summarize());
                    break;
                case PREWARM_CONNECTION:
                    final SabrPrewarmConnection prewarmConnection =
                            SabrPrewarmConnection.decode(partData);
                    decoded.setPrewarmConnection(prewarmConnection);
                    decoded.addGenericPartDescription(part.getType(),
                            prewarmConnection.summarize());
                    break;
                case START_BW_SAMPLING_HINT:
                case CONFIG:
                case HOSTNAME_CHANGE_HINT_DEPRECATED:
                case LIVE_METADATA_PROMISE:
                case LIVE_METADATA_PROMISE_CANCELLATION:
                case USTREAMER_VIDEO_AND_FORMAT_METADATA:
                case USTREAMER_SELECTED_MEDIA_STREAM:
                case ALLOWED_CACHED_FORMATS:
                case PAUSE_BW_SAMPLING_HINT:
                case ONESIE_PREFETCH_REJECTION:
                case TIMELINE_CONTEXT:
                case REQUEST_PIPELINING:
                case LAWNMOWER_POLICY:
                case SABR_ACK:
                case END_OF_TRACK:
                case CACHE_LOAD_POLICY:
                case LAWNMOWER_MESSAGING_POLICY:
                case PLAYBACK_DEBUG_INFO:
                    decoded.addGenericPartDescription(part.getType(),
                            describeGenericMessage(partData));
                    break;
                case REQUEST_IDENTIFIER:
                    final SabrRequestIdentifier requestIdentifier =
                            SabrRequestIdentifier.decode(partData);
                    decoded.setRequestIdentifier(requestIdentifier);
                    decoded.addGenericPartDescription(part.getType(),
                            requestIdentifier.summarize());
                    break;
                case REQUEST_CANCELLATION_POLICY:
                    final SabrRequestCancellationPolicy requestCancellationPolicy =
                            SabrRequestCancellationPolicy.decode(partData);
                    decoded.setRequestCancellationPolicy(requestCancellationPolicy);
                    decoded.addGenericPartDescription(part.getType(),
                            requestCancellationPolicy.summarize());
                    break;
                case SELECTABLE_FORMATS:
                    final SabrSelectableFormats selectableFormats =
                            SabrSelectableFormats.decode(partData);
                    decoded.setSelectableFormats(selectableFormats);
                    decoded.addGenericPartDescription(part.getType(),
                            selectableFormats.summarize());
                    break;
                default:
                    decoded.addUnknownPartType(part.getType());
                    decoded.addGenericPartDescription(part.getType(),
                            describeGenericMessage(partData));
                    break;
                }
            } catch (final SabrProtocolException e) {
                // One malformed protobuf message must not discard valid MEDIA from the rest of the
                // UMP response. Wire types 6/7 are invalid protobuf, and have been observed in a
                // transient NEXT_REQUEST_POLICY response. Ignore only that part and retain a
                // bounded diagnostic. MEDIA_HEADER corruption is still detected by the media
                // integrity checks (media-without-header) and goes through bounded recovery.
                decoded.addMalformedPart(part.getType(), part.getSize(), e);
            }
        }
        return decoded;
    }

    @Nonnull
    private static SabrNextRequestPolicy decodeNextRequestPolicy(@Nonnull final byte[] data,
                                                                 @Nonnull final SabrDecodedResponse decoded)
            throws SabrProtocolException {
        final SabrNextRequestPolicy policy = SabrNextRequestPolicy.decode(data);
        decoded.setNextRequestPolicy(policy);
        decoded.setBackoffTimeMs(policy.getBackoffTimeMs());
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 4 && field.getWireType() == SabrProto.WIRE_VARINT) {
                decoded.setBackoffTimeMs((int) field.getVarint());
            }
        }
        return policy;
    }

    @Nonnull
    private static String describeGenericMessage(@Nonnull final byte[] data) {
        try {
            return SabrProto.summarizeFields(data);
        } catch (final Exception e) {
            return "undecodable(" + data.length + " bytes)";
        }
    }
}
