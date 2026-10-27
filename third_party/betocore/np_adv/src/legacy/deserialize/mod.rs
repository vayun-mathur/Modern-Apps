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

//! V0 data element deserialization.
//!
//! This module only deals with the _contents_ of an advertisement, not the advertisement header.

use core::fmt;
use core::marker::PhantomData;

use crate::legacy::data_elements::tx_power::TxPowerDataElement;
use crate::{
    credential::v0::V0,
    legacy::{
        data_elements::{
            actions,
            de_type::{DeEncodedLength, DeTypeCode},
            DataElementDeserializeError, DeserializeDataElement, LengthMapper,
        },
        Ciphertext, PacketFlavor,
    },
    DeLengthOutOfRange,
};
use array_view::ArrayView;
use ldt_np_adv::{V0IdentityToken, V0Salt, NP_LDT_MAX_EFFECTIVE_PAYLOAD_LEN};
use nom::{bytes, combinator, number, Finish, Parser as _};

#[cfg(test)]
mod tests;

pub(crate) mod intermediate;

use crate::credential::matched::HasIdentityMatch;
use crate::legacy::data_elements::actions::ActionsDataElement;
use crate::legacy::data_elements::de_type::{DataElementType, DeActualLength};
use crate::legacy::data_elements::device_info::DeviceInfoDataElement;
use crate::legacy::data_elements::psm::PsmDataElement;
use crate::legacy::data_elements::DataElementDeserializeError::DuplicateDeTypes;
use crate::legacy::Plaintext;
/// exposed because the unencrypted case isn't just for intermediate: no further processing is needed
pub use intermediate::UnencryptedAdvContents;

/// Legacy advertisement parsing errors
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum AdvDeserializeError {
    /// Header or other structure was invalid
    InvalidStructure,
    /// DE contents must not be empty
    NoDataElements,
}

/// A data element with content length determined and validated per its type's length rules, but
/// no further decoding performed.
#[derive(Debug, PartialEq, Eq)]
pub(in crate::legacy) struct RawDataElement<'d, D: DataElementDeserializer> {
    pub(in crate::legacy) de_type: D::DeTypeDisambiguator,
    /// Byte array payload of the data element, without the DE header.
    pub(in crate::legacy) contents: &'d [u8],
}

impl<'d, D: DataElementDeserializer> RawDataElement<'d, D> {
    /// Parse an individual DE into its header and contents.
    fn parse(input: &'d [u8]) -> nom::IResult<&'d [u8], Self, DataElementDeserializeError> {
        let (input, (de_type, actual_len)) = combinator::map_res(
            combinator::map_opt(number::complete::u8, |de_header| {
                // header: LLLLTTTT
                let len = de_header >> 4;
                let de_type_num = de_header & 0x0F;

                // these can't fail since both inputs are 4 bits and will fit
                DeTypeCode::try_from(de_type_num).ok().and_then(|de_type| {
                    DeEncodedLength::try_from(len).ok().map(|encoded_len| (de_type, encoded_len))
                })
            }),
            |(de_type, encoded_len)| {
                D::map_encoded_len_to_actual_len(de_type, encoded_len).map_err(|e| match e {
                    LengthError::InvalidLength => {
                        DataElementDeserializeError::InvalidDeLength { de_type, len: encoded_len }
                    }
                    LengthError::InvalidType => {
                        DataElementDeserializeError::InvalidDeType { de_type }
                    }
                })
            },
        )
        .parse(input)?;

        combinator::map(bytes::complete::take(actual_len.as_usize()), move |contents| {
            RawDataElement { de_type, contents }
        })
        .parse(input)
    }
}

/// An iterator that parses the given data elements iteratively. In environments
/// where memory is not severely constrained, it is usually safer to collect
/// this into `Result<Vec<DeserializedDataElement>>` so the validity of the whole
/// advertisement can be checked before proceeding with further processing.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeIterator<'d, F> {
    delegate: GenericDeIterator<'d, F, StandardDeserializer>,
}

impl<'d, F> DeIterator<'d, F> {
    pub(in crate::legacy) fn new(data: &'d [u8]) -> Self {
        Self { delegate: GenericDeIterator::new(data) }
    }
}

impl<F: PacketFlavor> Iterator for DeIterator<'_, F> {
    type Item = Result<DeserializedDataElement<F>, DataElementDeserializeError>;

    fn next(&mut self) -> Option<Self::Item> {
        self.delegate.next()
    }
}

struct DeTypeBitFieldPosition(u16);
impl DeTypeBitFieldPosition {
    fn as_u16(&self) -> u16 {
        self.0
    }
}

impl From<u16> for DeTypeBitFieldPosition {
    fn from(value: u16) -> Self {
        DeTypeBitFieldPosition(value)
    }
}

impl From<DeTypeCode> for DeTypeBitFieldPosition {
    fn from(value: DeTypeCode) -> Self {
        match value.as_u8() {
            0 => 0.into(),
            1 => 0b00000000_00000001.into(),
            2 => 0b00000000_00000010.into(),
            3 => 0b00000000_00000100.into(),
            4 => 0b00000000_00001000.into(),
            5 => 0b00000000_00010000.into(),
            6 => 0b00000000_00100000.into(),
            7 => 0b00000000_01000000.into(),
            8 => 0b00000000_10000000.into(),
            9 => 0b00000001_00000000.into(),
            10 => 0b00000010_00000000.into(),
            11 => 0b00000100_00000000.into(),
            12 => 0b00001000_00000000.into(),
            13 => 0b00010000_00000000.into(),
            14 => 0b00100000_00000000.into(),
            15 => 0b01000000_00000000.into(),
            16 => 0b10000000_00000000.into(),
            _ => panic!("Invalid v0 De type code"),
        }
    }
}

/// The generified innards of [DeIterator] so that it's possible to also use test-only
/// deserializers.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::legacy) struct GenericDeIterator<'d, F, D> {
    /// Data to be parsed, containing a sequence of data elements in serialized
    /// form.
    data: &'d [u8],
    /// Keeps track of de_types as we iterate to ensure that multiple des of the same type
    /// are not present in an advertisement
    already_encountered: u16,
    _flavor_marker: PhantomData<F>,
    _deser_marker: PhantomData<D>,
}

impl<'d, F, D> GenericDeIterator<'d, F, D> {
    fn new(data: &'d [u8]) -> Self {
        Self {
            data,
            already_encountered: 0u16,
            _flavor_marker: Default::default(),
            _deser_marker: Default::default(),
        }
    }
}

impl<F: PacketFlavor, D: DataElementDeserializer> Iterator for GenericDeIterator<'_, F, D> {
    type Item = Result<D::Deserialized<F>, DataElementDeserializeError>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.data.is_empty() {
            return None;
        }
        let parse_result = combinator::cut(combinator::map_res(
            RawDataElement::parse,
            D::deserialize_de,
        ))
        .parse(self.data);

        match parse_result.finish() {
            Ok((rem, de)) => {
                if self.already_encountered
                    & DeTypeBitFieldPosition::from(de.de_type_code()).as_u16()
                    != 0
                {
                    return Some(Err(DuplicateDeTypes));
                }
                self.already_encountered |=
                    DeTypeBitFieldPosition::from(de.de_type_code()).as_u16();
                self.data = rem;
                Some(Ok(de))
            }
            Err(e) => Some(Err(e)),
        }
    }
}

/// Errors that can occur decrypting encrypted advertisements.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub(crate) enum DecryptError {
    /// Decrypting or verifying the advertisement ciphertext failed
    DecryptOrVerifyError,
}

/// All v0 DE types with deserialized contents.
#[derive(Debug, PartialEq, Eq, Clone)]
#[allow(missing_docs)]
pub enum DeserializedDataElement<F: PacketFlavor> {
    Actions(actions::ActionsDataElement<F>),
    TxPower(TxPowerDataElement),
    Psm(PsmDataElement),
    DeviceInfo(DeviceInfoDataElement),
}

impl<F: PacketFlavor> Deserialized for DeserializedDataElement<F> {
    fn de_type_code(&self) -> DeTypeCode {
        match self {
            DeserializedDataElement::Actions(_) => {
                DeTypeCode::try_from(ActionsDataElement::<F>::DE_TYPE_CODE.as_u8())
                    .expect("Actions type code is valid so this will always succeed")
            }
            DeserializedDataElement::TxPower(_) => {
                DeTypeCode::try_from(TxPowerDataElement::DE_TYPE_CODE.as_u8())
                    .expect("TxPower type code is valid so this will always succeed")
            }
            DeserializedDataElement::Psm(_) => {
                DeTypeCode::try_from(PsmDataElement::DE_TYPE_CODE.as_u8())
                    .expect("Psm type code is valid so this will always succeed")
            }
            DeserializedDataElement::DeviceInfo(_) => {
                DeTypeCode::try_from(DeviceInfoDataElement::DE_TYPE_CODE.as_u8())
                    .expect("DeviceInfo type code is valid so this will always succeed")
            }
        }
    }
}

impl<F: PacketFlavor> DeserializedDataElement<F> {
    /// Returns the DE type as a u8
    #[cfg(feature = "devtools")]
    pub fn de_type_code(&self) -> u8 {
        match self {
            DeserializedDataElement::Actions(_) => ActionsDataElement::<F>::DE_TYPE_CODE.as_u8(),
            DeserializedDataElement::TxPower(_) => TxPowerDataElement::DE_TYPE_CODE.as_u8(),
            DeserializedDataElement::Psm(_) => PsmDataElement::DE_TYPE_CODE.as_u8(),
            DeserializedDataElement::DeviceInfo(_) => DeviceInfoDataElement::DE_TYPE_CODE.as_u8(),
        }
    }

    /// Returns the serialized contents of the DE
    #[cfg(feature = "devtools")]
    #[allow(clippy::unwrap_used)]
    pub fn de_contents(&self) -> alloc::vec::Vec<u8>
    where
        actions::ActionsDataElement<F>: crate::legacy::data_elements::SerializeDataElement<F>,
    {
        use crate::legacy::data_elements::{DataElementSerializationBuffer, SerializeDataElement};

        let mut sink = DataElementSerializationBuffer::new(super::NP_MAX_ADV_CONTENT_LEN).unwrap();
        match self {
            DeserializedDataElement::Actions(a) => a.serialize_contents(&mut sink),
            DeserializedDataElement::TxPower(t) => {
                SerializeDataElement::<F>::serialize_contents(t, &mut sink)
            }
            DeserializedDataElement::Psm(h) => {
                SerializeDataElement::<F>::serialize_contents(h, &mut sink)
            }
            DeserializedDataElement::DeviceInfo(d) => {
                SerializeDataElement::<F>::serialize_contents(d, &mut sink)
            }
        }
        .unwrap();
        sink.into_inner().into_inner().as_slice().to_vec()
    }
}

/// Contents of an LDT advertisement after decryption.
#[derive(Debug, PartialEq, Eq)]
pub struct DecryptedAdvContents {
    identity_token: V0IdentityToken,
    salt: V0Salt,
    /// The decrypted data in this advertisement after the identity token.
    /// This is hopefully a sequence of serialized data elements, but that hasn't been validated
    /// yet at construction time.
    data: ArrayView<u8, { NP_LDT_MAX_EFFECTIVE_PAYLOAD_LEN }>,
}

impl DecryptedAdvContents {
    /// Returns a new DecryptedAdvContents with the provided contents.
    fn new(
        metadata_key: V0IdentityToken,
        salt: V0Salt,
        data: ArrayView<u8, { NP_LDT_MAX_EFFECTIVE_PAYLOAD_LEN }>,
    ) -> Self {
        Self { identity_token: metadata_key, salt, data }
    }

    /// Iterator over the data elements in an advertisement, except for any DEs related to resolving
    /// the identity or otherwise validating the payload (e.g. any identity DEs like Private
    /// Identity).
    pub fn data_elements(&self) -> DeIterator<'_, Ciphertext> {
        DeIterator::new(self.data.as_slice())
    }

    /// The salt used for decryption of this advertisement.
    pub fn salt(&self) -> V0Salt {
        self.salt
    }

    #[cfg(test)]
    pub(in crate::legacy) fn generic_data_elements<D: DataElementDeserializer>(
        &self,
    ) -> GenericDeIterator<Ciphertext, D> {
        GenericDeIterator::new(self.data.as_slice())
    }
}

impl HasIdentityMatch for DecryptedAdvContents {
    type Version = V0;
    fn identity_token(&self) -> V0IdentityToken {
        self.identity_token
    }
}

pub(in crate::legacy) trait Deserialized:
    fmt::Debug + PartialEq + Eq + Clone
{
    fn de_type_code(&self) -> DeTypeCode;
}

/// Overall strategy for deserializing adv contents (once decrypted, if applicable) into data
/// elements
pub(in crate::legacy) trait DataElementDeserializer: Sized {
    /// Disambiguate the intermediate form of a DE
    type DeTypeDisambiguator: Copy;
    /// The fully deserialized form of a DE
    type Deserialized<F: PacketFlavor>: Deserialized;

    /// Map the encoded len found in a DE header to the actual len that should be consumed from the
    /// advertisement payload
    fn map_encoded_len_to_actual_len(
        de_type: DeTypeCode,
        encoded_len: DeEncodedLength,
    ) -> Result<(Self::DeTypeDisambiguator, DeActualLength), LengthError>;

    /// Deserialize into a [Self::Deserialized] to expose DE-type-specific data representations.
    ///
    /// Returns `Err` if the contents of the raw DE can't be deserialized into the corresponding
    /// DE's representation.
    fn deserialize_de<F: PacketFlavor>(
        raw_de: RawDataElement<Self>,
    ) -> Result<Self::Deserialized<F>, DataElementDeserializeError>;
}

/// Possible errors when mapping DE encoded lengths to actual lengths
pub(in crate::legacy) enum LengthError {
    /// The DE type was known, but the encoded length was invalid
    InvalidLength,
    /// The DE type was not unrecognized
    InvalidType,
}

impl From<DeLengthOutOfRange> for LengthError {
    fn from(_value: DeLengthOutOfRange) -> Self {
        Self::InvalidLength
    }
}

/// The default deserialization strategy that maps type codes to [DataElementType], and deserializes
/// to [DeserializedDataElement].
#[derive(Debug, PartialEq, Eq, Clone)]
struct StandardDeserializer;

impl DataElementDeserializer for StandardDeserializer {
    type DeTypeDisambiguator = DataElementType;
    type Deserialized<F: PacketFlavor> = DeserializedDataElement<F>;

    fn map_encoded_len_to_actual_len(
        de_type: DeTypeCode,
        encoded_len: DeEncodedLength,
    ) -> Result<(Self::DeTypeDisambiguator, DeActualLength), LengthError> {
        match de_type {
            TxPowerDataElement::DE_TYPE_CODE => {
                <TxPowerDataElement as DeserializeDataElement>::LengthMapper::map_encoded_len_to_actual_len(encoded_len)
                    .map_err(|e| e.into())
                    .map(|l| (DataElementType::TxPower, l))
            }
            ActionsDataElement::<Plaintext>::DE_TYPE_CODE => {
                <ActionsDataElement<Plaintext> as DeserializeDataElement>::LengthMapper::map_encoded_len_to_actual_len(encoded_len)
                    .map_err(|e| e.into())
                    .map(|l| (DataElementType::Actions, l))
            }
            PsmDataElement::DE_TYPE_CODE => {
                <PsmDataElement as DeserializeDataElement>::LengthMapper::map_encoded_len_to_actual_len(encoded_len)
                    .map_err(|e| e.into())
                    .map(|l| (DataElementType::Psm, l))

            }
            DeviceInfoDataElement::DE_TYPE_CODE => {
                <DeviceInfoDataElement as DeserializeDataElement>::LengthMapper::map_encoded_len_to_actual_len(encoded_len)
                    .map_err(|e| e.into())
                    .map(|l| (DataElementType::DeviceInfo, l))
            }
            _ => Err(LengthError::InvalidType),
        }
    }

    fn deserialize_de<F: PacketFlavor>(
        raw_de: RawDataElement<Self>,
    ) -> Result<Self::Deserialized<F>, DataElementDeserializeError> {
        match raw_de.de_type {
            DataElementType::Actions => {
                actions::ActionsDataElement::deserialize::<F>(raw_de.contents)
                    .map(DeserializedDataElement::Actions)
            }
            DataElementType::TxPower => TxPowerDataElement::deserialize::<F>(raw_de.contents)
                .map(DeserializedDataElement::TxPower),
            DataElementType::Psm => {
                PsmDataElement::deserialize::<F>(raw_de.contents).map(DeserializedDataElement::Psm)
            }
            DataElementType::DeviceInfo => DeviceInfoDataElement::deserialize::<F>(raw_de.contents)
                .map(DeserializedDataElement::DeviceInfo),
        }
    }
}
