// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Covers the first half of section parsing before decryption, if relevant, is
//! attempted.

use crate::{
    array_vec::ArrayVecOption,
    extended::{
        deserialize::{
            encrypted_section::{
                EncryptedSectionContents, MicEncryptedSection, SectionIdentityResolutionContents,
            },
            section::header::{
                CiphertextExtendedIdentityToken, EncryptedSectionHeader, SectionHeader,
            },
            DataElementParsingIterator, Section, SectionMic,
        },
        salt::MultiSalt,
        EncodingType, NP_V1_ADV_MAX_SECTION_COUNT,
    },
    header::V1AdvHeader,
};

use crypto_provider::CryptoProvider;
use nom::{combinator, error, Parser as _};

#[cfg(feature = "devtools")]
use crate::{
    credential::v1::V1DiscoveryCryptoMaterial, deserialization_arena::DeserializationArenaAllocator,
};
#[cfg(feature = "devtools")]
use crate::{deserialization_arena::ArenaOutOfSpace, extended::NP_ADV_MAX_SECTION_LEN};
#[cfg(feature = "devtools")]
use array_view::ArrayView;

#[cfg(test)]
pub(crate) mod tests;

/// Parse into [IntermediateSection]s, exposing the underlying parsing errors.
/// Consumes all of `adv_body`.
pub(crate) fn parse_sections(
    adv_header: V1AdvHeader,
    adv_body: &[u8],
) -> Result<
    ArrayVecOption<IntermediateSection<'_>, NP_V1_ADV_MAX_SECTION_COUNT>,
    nom::Err<error::Error<&[u8]>>,
> {
    let mut result = ArrayVecOption::default();

    // Parse through at most NP_V1_ADV_MAX_SECTION_COUNT
    // sections, bailing with errors if any section with a
    // known encoding type fails to parse, or if the encoding
    // types are not sorted.
    //
    // Not using nom parser combinators here due to the relatively complex
    // logic around deciding whether/not to continue parsing,
    // which is not entirely based on the number of sections
    // nor on the content of the sections alone.
    let mut remaining_contents = adv_body;
    let mut largest_encountered_encoding_type: u8 = 0;
    while !remaining_contents.is_empty() && result.len() < NP_V1_ADV_MAX_SECTION_COUNT {
        // Attempt to grab a new section.
        let (updated_remaining_contents, maybe_section_contents) =
            SectionContents::parse(remaining_contents)?;
        remaining_contents = updated_remaining_contents;

        let Ok(section_contents) = maybe_section_contents else {
            // We hit an encoding type which we do not recognize. Truncate the
            // advertisement to only what we've parsed and bail from the loop.
            remaining_contents = &[];
            break;
        };
        // Ensure that the encoding type is nondecreasing through the adverisement sections.
        let current_encoding_type = section_contents.header.encoding_type().0;
        if current_encoding_type < largest_encountered_encoding_type {
            return Err(nom::Err::Failure(nom::error::Error::new(
                remaining_contents,
                nom::error::ErrorKind::Verify,
            )));
        }
        largest_encountered_encoding_type = current_encoding_type;

        // Attempt to lift the section contents to an [`IntermediateSection`].
        let intermediate_section = IntermediateSection::try_parse(adv_header, section_contents)
            .ok_or(nom::Err::Failure(nom::error::Error::new(
                remaining_contents,
                nom::error::ErrorKind::NonEmpty,
            )))?;
        result.push(intermediate_section);
    }
    // We've parsed as many sections as we can, ensure there's at least one section
    // and check whether/not there are trailing bytes to determine overall success/failure.
    if !result.is_empty() {
        if remaining_contents.is_empty() {
            Ok(result)
        } else {
            Err(nom::Err::Failure(nom::error::Error::new(
                remaining_contents,
                nom::error::ErrorKind::Complete,
            )))
        }
    } else {
        Err(nom::Err::Failure(nom::error::Error::new(
            remaining_contents,
            nom::error::ErrorKind::Many1,
        )))
    }
}

/// A partially processed section that hasn't been decrypted (if applicable) yet.
#[derive(PartialEq, Eq, Debug)]
pub(crate) enum IntermediateSection<'a> {
    /// A section that was not encrypted, e.g. a public identity or no-identity section.
    Plaintext(PlaintextSection<'a>),
    /// A section whose contents were encrypted, e.g. a private identity section.
    Ciphertext(CiphertextSection<'a>),
}

impl<'a> IntermediateSection<'a> {
    /// Given some section contents, attempts to construct
    /// an [`IntermediateSection`] out of them. May return
    /// `None` if the section is malformed according to
    /// the rules of its encoding type.
    pub(crate) fn try_parse(
        adv_header: V1AdvHeader,
        section_contents: SectionContents<'a>,
    ) -> Option<IntermediateSection<'a>> {
        fn split_at_mic(contents: &[u8]) -> Option<(&[u8], SectionMic)> {
            contents.len().checked_sub(SectionMic::CONTENTS_LEN).map(|len_before_mic| {
                let (before_mic, mic) = contents.split_at(len_before_mic);
                let mic = SectionMic::try_from(mic).expect("MIC length checked above");

                (before_mic, mic)
            })
        }

        fn build_mic_section(
            adv_header: V1AdvHeader,
            format_byte: u8,
            salt: MultiSalt,
            token: CiphertextExtendedIdentityToken,
            contents_len: u8,
            contents: &[u8],
        ) -> Option<IntermediateSection<'_>> {
            split_at_mic(contents).map(|(before_mic, mic)| {
                IntermediateSection::Ciphertext(CiphertextSection::MicEncrypted(
                    MicEncryptedSection {
                        contents: EncryptedSectionContents::new(
                            adv_header,
                            format_byte,
                            salt,
                            token,
                            contents_len,
                            before_mic,
                        ),
                        mic,
                    },
                ))
            })
        }
        match section_contents.header {
            SectionHeader::Unencrypted => Some(IntermediateSection::Plaintext(
                PlaintextSection::new(section_contents.contents),
            )),
            SectionHeader::Encrypted(e) => {
                let format_byte = section_contents.format_byte;
                let contents = section_contents.contents;
                let contents_len = section_contents.contents_len;
                match e {
                    EncryptedSectionHeader::MicShortSalt { salt, token } => build_mic_section(
                        adv_header,
                        format_byte,
                        salt.into(),
                        token,
                        contents_len,
                        contents,
                    ),
                    EncryptedSectionHeader::MicExtendedSalt { salt, token } => build_mic_section(
                        adv_header,
                        format_byte,
                        salt.into(),
                        token,
                        contents_len,
                        contents,
                    ),
                }
            }
        }
    }
}

/// Components of a section after header decode, but before decryption or DE parsing.
///
/// This is just the first stage of parsing sections, followed by [IntermediateSection].
#[derive(PartialEq, Eq, Debug)]
pub(crate) struct SectionContents<'adv> {
    /// The section header format byte saved for later use in verification
    pub(crate) format_byte: u8,
    /// Section header contents which includes salt + identity token
    pub(crate) header: SectionHeader,
    /// Contents of the section after the header.
    /// No validation is performed on the contents.
    pub(crate) contents: &'adv [u8],
    /// The length of the contents stored as an u8.
    pub(crate) contents_len: u8,
}

impl<'adv> SectionContents<'adv> {
    /// Attempts to parse the contents of a section (including the header)
    /// from the given bytes.
    ///
    /// If the encoding type of the section header was unrecognized,
    /// this method will bail on attempting to parse the entire section
    /// contents and will instead consume only the first encoding-type byte,
    /// but the parser WILL succeed as a nom parser.
    pub(crate) fn parse(input: &'adv [u8]) -> nom::IResult<&'adv [u8], Result<Self, EncodingType>> {
        match SectionHeader::parse(input)? {
            (input, Ok(header)) => {
                // Header parsed successfully, with a known encoding type.
                let format_byte = header.encoding_type().0;
                let (input, contents_len) = combinator::verify(nom::number::complete::u8, |b| {
                    // length must be non-zero.
                    *b != 0
                })
                .parse(input)?;

                let (input, contents) = nom::bytes::complete::take(contents_len).parse(input)?;

                Ok((input, Ok(Self { format_byte, header, contents, contents_len })))
            }
            (input, Err(encoding_type)) => {
                // Unknown encoding type.
                Ok((input, Err(encoding_type)))
            }
        }
    }
}

/// A plaintext section deserialized from a V1 advertisement.
#[derive(PartialEq, Eq, Debug)]
pub struct PlaintextSection<'adv> {
    contents: &'adv [u8],
}

impl<'adv> PlaintextSection<'adv> {
    pub(crate) fn new(contents: &'adv [u8]) -> Self {
        Self { contents }
    }
}

impl<'adv> Section<'adv> for PlaintextSection<'adv> {
    fn iter_data_elements(&self) -> DataElementParsingIterator<'adv> {
        DataElementParsingIterator::new(self.contents, None)
    }
}

#[derive(PartialEq, Eq, Debug)]
pub(crate) enum CiphertextSection<'a> {
    MicEncrypted(MicEncryptedSection<'a>),
}

#[cfg(feature = "devtools")]
impl<'a> CiphertextSection<'a> {
    /// Try decrypting into some raw bytes given some raw unsigned crypto-material.
    pub(crate) fn try_resolve_identity_and_decrypt<
        C: V1DiscoveryCryptoMaterial,
        P: CryptoProvider,
    >(
        &self,
        allocator: &mut DeserializationArenaAllocator<'a>,
        crypto_material: &C,
    ) -> Option<Result<ArrayView<u8, { NP_ADV_MAX_SECTION_LEN }>, ArenaOutOfSpace>> {
        match self {
            CiphertextSection::MicEncrypted(x) => match x.contents.salt {
                MultiSalt::Short(_) => x.try_resolve_short_salt_identity_and_decrypt::<P>(
                    allocator,
                    &crypto_material.mic_short_salt_identity_resolution_material::<P>(),
                ),
                MultiSalt::Extended(_) => x.try_resolve_extended_salt_identity_and_decrypt::<P>(
                    allocator,
                    &crypto_material.mic_extended_salt_identity_resolution_material::<P>(),
                ),
            },
        }
    }
}

impl CiphertextSection<'_> {
    /// Return the data needed to resolve identities.
    ///
    /// In the typical case of trying many identities across a few sections,
    /// these should be calculated once for all relevant sections, then re-used
    /// for all identity match attempts.
    pub(crate) fn identity_resolution_contents<C: CryptoProvider>(
        &self,
    ) -> SectionIdentityResolutionContents {
        match self {
            CiphertextSection::MicEncrypted(x) => {
                x.contents.compute_identity_resolution_contents::<C>()
            }
        }
    }
}
