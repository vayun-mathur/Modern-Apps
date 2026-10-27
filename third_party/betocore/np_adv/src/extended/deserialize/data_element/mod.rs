// Copyright 2024 Google LLC
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

//! Parsing logic for V1 data elements, header + contents

use crate::extended::salt::DeSalt;
use crate::extended::{
    de_requires_extended_bit,
    de_type::{DeType, HasDEType, OptionDeType},
    deserialize, DeLength,
};
use core::{fmt, marker::PhantomData};
use nom::{branch, bytes, combinator, error, number, sequence, Parser as _};
use np_hkdf::v1_salt::ExtendedV1Salt;

#[cfg(test)]
mod tests;

/// A representation of a V1 data element with a particular
/// type-code, together with a mechanism to convert to
/// the alternative representation. The `Self` type is taken
/// to be this alternative representation.
pub trait DeserializedDataElement<'a>: HasDEType
where
    Self: Sized + 'a,
{
    /// The type of errors rasied by `Self::try_deserialize`.
    type DeserializationError: 'a;

    /// Attempts to convert a DE with the `Self::DE_TYPE` type-code,
    /// the given offset within the section, and the given
    /// contents into an instance of this alternative representation.
    ///
    /// This method may possibly fail with an error.
    ///
    /// The returned representation may borrow from the raw slice contents
    /// of the DE payload for situations where "zero-copy" deserialization
    /// makes sense.
    fn try_deserialize(
        maybe_salt: Option<&DeSalt>,
        contents: &'a [u8],
    ) -> Result<Self, Self::DeserializationError>;
}

/// Type alias for the result type returned by
/// [`DeserializedDataElement<'static>#try_deserialize`].
/// While the `'static` lifetime will almost-never appear as the actual lifetime
/// in a call to `try_deserialize`, this is useful for referring to the result
/// type when the method's return type is in fact invariant with respect
/// to the lifetime, as is the case for owned (lifetime-parameter-free)
/// [`DeserializedDataElement`]-implementing structures via parametricity.
pub type OwnedDEDeserializationResult<D> = DEDeserializationResult<'static, D>;

/// Type alias for the result type returned by [`DeserializedDataElement<'a>#try_deserialize`]
pub type DEDeserializationResult<'a, D> =
    Result<D, <D as DeserializedDataElement<'a>>::DeserializationError>;

/// Common trait for the results obtained via attempting to deserialize
/// a [`DataElement`] into one or more DE-type-specific form(s).
/// May be generated from individual [`DeserializedDataElement`]s
/// via the [`define_data_element_deserializer`] macro.
pub trait DataElementDeserializationResult<'adv>
where
    Self: Sized + 'adv,
{
    /// Attempts to deserialize a data element to this deserialized representation,
    /// passing the DE along if the DE type code or other data means that it may
    /// not be deserialized to this representation.
    fn try_deserialize(data_element: DataElement<'adv>) -> Result<Self, DataElement<'adv>>;
}

/// An error raised when attempting to parse and deserialize a data
/// element to a given type implementing [`DataElementDeserializationResult`].
pub enum DataElementDeserializationError<'adv> {
    /// The error occurred when attempting to parse the data element
    /// from the containing section.
    ParseError(DataElementParseError),
    /// The deserializer gave up on trying to parse the data element,
    /// likely because it doesn't have a recognized DE type.
    Unrecognized(DataElement<'adv>),
}

/// Iterator which wraps a [`DataElementParsingIterator`] to perform additional
/// per-DE deserialization steps beyond just interpreting generic [`DataElement`]s.
pub struct DataElementDeserializingIterator<'adv, F: DataElementDeserializationResult<'adv>> {
    iter: DataElementParsingIterator<'adv>,
    _phantom: PhantomData<fn() -> F>,
}

impl<'adv, F: DataElementDeserializationResult<'adv>> From<DataElementParsingIterator<'adv>>
    for DataElementDeserializingIterator<'adv, F>
{
    fn from(iter: DataElementParsingIterator<'adv>) -> Self {
        Self { iter, _phantom: Default::default() }
    }
}

impl<'adv, F: DataElementDeserializationResult<'adv>> Iterator
    for DataElementDeserializingIterator<'adv, F>
{
    type Item = Result<F, DataElementDeserializationError<'adv>>;
    fn next(&mut self) -> Option<Self::Item> {
        self.iter.next().map(|maybe_data_element| match maybe_data_element {
            Ok(data_element) => {
                <F as DataElementDeserializationResult<'adv>>::try_deserialize(data_element)
                    .map_err(DataElementDeserializationError::Unrecognized)
            }
            Err(parse_err) => Err(DataElementDeserializationError::ParseError(parse_err)),
        })
    }
}

#[macro_export]
/// Macro to define [`DataElementDeserializationResult`]s by "gluing"
/// together the associated result types of one or more [`DeserializedDataElement`]s
/// which may or may not have data-to-be-borrowed from advertisement contents.
/// The generated `enum` has a variant for each distinct DE type specified as part of the
/// macro, with payloads set as the appropriate choice of either
/// the associated [`DEDeserializationResult`] or [`OwnedDEDeserializationResult`] for
/// the type, and the semantics of the `try_deserialize` method are specified to
/// match against the incoming DE type and forward deserialization to the
/// `try_deserialize` method on the associated deserialized DE type.
///
/// Caveats about the current form of the macro:
///
/// - The wrapped enum declaration may have a lifetime if the data is borrowed,
///   but if so, this lifetime currently _must_ be named `'adv` (after the
///   fact that any borrowed contents ultimately are of the same lifetime
///   as a particular deserialized advertisement.)
///
/// - Any variants which borrow data from a deserialized advertisement
///   must also be listed before variants which own data.
///
/// - Enumeration discriminants cannot be manually specified. (However,
///   there is a generated `de_type` method which allows obtaining
///   the data element type code of a deserialized DE).
///
/// # Examples
/// ```
/// #[macro_use] extern crate np_adv;
/// use np_adv::extended::data_elements::*;
/// define_data_element_deserializer! {
///    /// Sample deserialization result type which
///    /// owns all its data, and includes result types
///    /// for deserializing Tx Power and context-sync
///    /// sequence number data elements.
///    #[derive(Debug, Clone)]
///    pub enum OwnedDEDeserializationResult owns {
///        /// A transmission power
///        TxPower: TxPowerDataElement,
///        /// A context sync sequence number
///        ContextSyncSeqNum: ContextSyncSeqNumDataElement,
///    }
/// }
///
/// // Generated result enum:
/// // /// Sample deserialization result type which
/// // /// owns all its data, and includes result types
/// // /// for deserializing Tx Power and context-sync
/// // /// sequence number data elements.
/// // #[derive(Debug, Clone)]
/// // pub enum OwnedDeserializationResult {
/// //    TxPower(Result<TxPowerDataElement, TxPowerMalformed>),
/// //    ContextSyncSeqNum(Result<ContextSyncSeqNumDataElement, ContextSyncSeqNumMalformed>),
/// // }
/// // impl<'adv> DataElementDeserializationResult<'adv> for OwnedDeserializationResult {
/// // ...
/// // }
///
/// define_data_element_deserializer! {
///     /// Sample deserialization result type which
///     /// borrows all its data, and includes result types
///     /// for deserializing Actions and a Cast ID.
///     ///
///     /// Note how we can specify arbitrary attributes (including derives)
///     /// on the generated enum and arbitrary visibility specifiers.
///     #[derive(Debug, Clone)]
///     pub(crate) enum BorrowedDEDeserializationResult<'adv> borrows {
///         /// A collection of actions.
///         Actions: DeserializedActionsDE<'adv>,
///         /// A Google Cast ID.
///         CastId: CastIdDataElement<'adv>,
///     }
/// }
///
/// define_data_element_deserializer! {
///     /// Sample deserialization result type which
///     /// borrows some data for an Actions DE target, but
///     /// owns other data for a Tx Power DE target.
///     #[repr(u8)]
///     enum CombinedDEDeserializationResult<'adv> borrows {
///         /// A collection of actions.
///         Actions: DeserializedActionsDE<'adv>,
///     } owns {
///         /// A transmission power
///         TxPower: TxPowerDataElement,
///     }
/// }
/// ```
macro_rules! define_data_element_deserializer {
    // The combined type is owned.
    (
        $(#[$combined_meta:meta])*
        $visibility:vis enum $combined_type_name:ident owns {
            $(
                $(#[$owned_constituent_meta:meta])*
                $owned_constituent_name:ident : $owned_constituent_type:ident
            ),+ $(,)?
        }
    )
    => {
        $(#[$combined_meta])*
        $visibility enum $combined_type_name {
            $(
                $(#[$owned_constituent_meta])*
                $owned_constituent_name($crate::extended::deserialize::data_element::OwnedDEDeserializationResult<
                    $owned_constituent_type
                >
            )),+
        }
        impl $combined_type_name {
            /// Gets the data-element type of the deserialized DE.
            $visibility fn de_type(&self) -> $crate::extended::de_type::DeType {
                match &self {
                    $(Self::$owned_constituent_name(_) =>
                        <$owned_constituent_type as $crate::extended::de_type::HasDEType>::DE_TYPE),+
                }
            }
        }
        impl<'adv> $crate::extended::deserialize::data_element::DataElementDeserializationResult<'adv> for $combined_type_name {
            fn try_deserialize(data_element: $crate::extended::deserialize::data_element::DataElement<'adv>)
                -> Result<Self, $crate::extended::deserialize::data_element::DataElement<'adv>> {
                    match data_element.de_type() {
                        $(
                            <$owned_constituent_type as $crate::extended::de_type::HasDEType>::DE_TYPE => {
                                let result = <$owned_constituent_type as $crate::extended::deserialize::data_element::DeserializedDataElement<'adv>>::
                                    try_deserialize(data_element.salt().as_ref(), data_element.contents());
                                Ok(
                                    $combined_type_name::$owned_constituent_name(result)
                                )
                            }
                        ),+ ,
                        _ => {
                            Err(data_element)
                        },
                    }
            }
        }
    };

    // The combined type is (at least partially) borrowed.
    (
        $(#[$combined_meta:meta])*
        $visibility:vis enum $combined_type_name:ident <'adv> borrows {
         $(
             $(#[$borrowed_constituent_meta:meta])*
             $borrowed_constituent_name:ident : $borrowed_constituent_type:ident <'adv>
          ),+ $(,)?
        } $( owns {
         $(
             $(#[$owned_constituent_meta:meta])*
             $owned_constituent_name:ident : $owned_constituent_type:ident
          ),* $(,)?
        })?
    )

    => {
        $(#[$combined_meta])*
        $visibility enum $combined_type_name<'adv> {
            $(
                $(#[$borrowed_constituent_meta])*
                $borrowed_constituent_name($crate::extended::deserialize::data_element::DEDeserializationResult<
                    'adv,
                    $borrowed_constituent_type<'adv>
                >
            )),+ ,
            $($(
                $(#[$owned_constituent_meta])*
                $owned_constituent_name($crate::extended::deserialize::data_element::OwnedDEDeserializationResult<
                    $owned_constituent_type
                >
            )),*)?
        }
        impl<'adv> $combined_type_name<'adv> {
            /// Gets the data-element type of the deserialized DE.
            $visibility fn de_type(&self) -> $crate::extended::de_type::DeType {
                match &self {
                    $(Self::$borrowed_constituent_name(_) =>
                        <$borrowed_constituent_type<'adv> as $crate::extended::de_type::HasDEType>::DE_TYPE),+ ,
                    $($(Self::$owned_constituent_name(_) =>
                        <$owned_constituent_type as $crate::extended::de_type::HasDEType>::DE_TYPE),*)?
                }
            }
        }

        impl<'adv> $crate::extended::deserialize::data_element::DataElementDeserializationResult<'adv> for $combined_type_name<'adv> {
            fn try_deserialize(data_element: $crate::extended::deserialize::data_element::DataElement<'adv>)
                -> Result<Self, $crate::extended::deserialize::data_element::DataElement<'adv>> {
                    match data_element.de_type() {
                        $(
                            <$borrowed_constituent_type<'adv> as $crate::extended::de_type::HasDEType>::DE_TYPE => {
                                let result = <$borrowed_constituent_type as $crate::extended::deserialize::data_element::DeserializedDataElement<'adv>>::
                                    try_deserialize(data_element.salt().as_ref(), data_element.contents());
                                Ok(
                                    $combined_type_name::$borrowed_constituent_name(result)
                                )
                            }
                        ),+ ,
                        $($(
                            <$owned_constituent_type as $crate::extended::de_type::HasDEType>::DE_TYPE => {
                                let result = <$owned_constituent_type as $crate::extended::deserialize::data_element::DeserializedDataElement<'adv>>::
                                    try_deserialize(data_element.salt().as_ref(), data_element.contents());
                                Ok(
                                    $combined_type_name::$owned_constituent_name(result)
                                )
                            }
                        ),* ,)?
                        _ => {
                            Err(data_element)
                        },
                    }
            }
        }
    };
}

pub use define_data_element_deserializer;

/// A deserialized data element in a section.
///
/// The DE has been processed to the point of exposing a DE type, a way to get derived salts,
/// and its contents as a `&[u8]`, but no DE-type-specific processing has been performed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DataElement<'adv> {
    proto_de: ProtoDataElement<'adv>,
    /// Copy of the containing section's extended salt, if any.
    salt: Option<ExtendedV1Salt>,
}

impl<'adv> DataElement<'adv> {
    /// Gets derived salts for this data element, if this is possible.
    /// (Note: In the wild, only decrypted sections with extended salts
    /// will have this populated.)
    pub fn salt(&self) -> Option<DeSalt> {
        self.salt.as_ref().map(|salt| DeSalt { de_type: self.de_type(), salt: *salt })
    }
    /// The type of the DE
    pub fn de_type(&self) -> DeType {
        self.proto_de.de_type
    }
    /// The raw bytes of this DE's payload
    pub fn contents(&self) -> &'adv [u8] {
        self.proto_de.contents
    }
    /// Constructs a data element by optionally augmenting the given [`ProtoDataElement`]
    /// with an [`ExtendedV1Salt`] if the containing section makes it possible to
    /// derive salts for the DE.
    pub fn from_proto_de(proto_de: ProtoDataElement<'adv>, salt: Option<ExtendedV1Salt>) -> Self {
        Self { proto_de, salt }
    }
    /// Constructs a data element from the DE header, the contents of the DE,
    /// and an optional [`DeSalt`] for computing derived salts.
    pub fn new(de_type: DeType, contents: &'adv [u8], salt: Option<ExtendedV1Salt>) -> Self {
        let proto_de = ProtoDataElement { de_type, contents };
        Self { proto_de, salt }
    }

    /// Constructs a data element without the ability to derive DE salts
    /// from the DE type and its contents.
    pub fn unsalted(de_type: DeType, contents: &'adv [u8]) -> Self {
        Self::new(de_type, contents, None)
    }
}

/// An iterator that parses the given [`DataElement`]s iteratively. In environments where memory is
/// not severely constrained, it is usually safer to collect this into `Result<Vec<OffsetDataElement>>`
/// so the validity of the whole advertisement can be checked before proceeding with further
/// processing.
pub struct DataElementParsingIterator<'adv> {
    /// A reference to the slice of section contents that we're parsing.
    input: &'adv [u8],
    /// The data element type of the most-recently-parsed DE (if any).
    last_encountered_de_type: OptionDeType,
    /// A copy of the enclosing section's extended salt (if any).
    maybe_extended_salt: Option<ExtendedV1Salt>,
}

impl<'adv> DataElementParsingIterator<'adv> {
    pub(crate) fn new(input: &'adv [u8], maybe_extended_salt: Option<ExtendedV1Salt>) -> Self {
        Self { input, last_encountered_de_type: OptionDeType::NONE, maybe_extended_salt }
    }
}

/// Each [`Self::Item`] is a parsing result of a Data Element. The result includes a parsed
/// Data Element for success, or [`DataElementParseError`] for failure.
/// Suggest to throw away all the Data Elements from the iterator if one Data Element is corrupted.
/// The caller can collect the Result to the top level as [`Result<Vec<_>, _>`] before iterating
/// the Data Elements.
impl<'adv> Iterator for DataElementParsingIterator<'adv> {
    type Item = Result<DataElement<'adv>, DataElementParseError>;

    fn next(&mut self) -> Option<Self::Item> {
        match ProtoDataElement::parse(self.input) {
            Ok((rem, proto_de)) => {
                // Parsed the DE successfully, verify that the type-code is
                // strictly increasing.
                let current_de_type: OptionDeType = proto_de.de_type.into();
                if current_de_type > self.last_encountered_de_type {
                    self.input = rem;
                    self.last_encountered_de_type = current_de_type;
                    Some(Ok(DataElement { proto_de, salt: self.maybe_extended_salt }))
                } else {
                    Some(Err(DataElementParseError::TypeCodesOutOfOrder))
                }
            }
            Err(nom::Err::Failure(e)) => Some(Err(DataElementParseError::NomError(e.code))),
            Err(nom::Err::Incomplete(_)) => {
                panic!("Should always complete since we are parsing using the `nom::complete` APIs")
            }
            Err(nom::Err::Error(_)) => {
                // nom `Error` is recoverable, it usually means we should move on the parsing the
                // next section. There is nothing after data elements within a section, so we just
                // check that there is no remaining data.
                if !self.input.is_empty() {
                    return Some(Err(DataElementParseError::UnexpectedDataAfterEnd));
                }
                None
            }
        }
    }
}

/// The error that may arise while parsing data elements.
#[derive(Debug, PartialEq, Eq)]
pub enum DataElementParseError {
    /// Unexpected data found after the end of the data elements portion. This means either the
    /// parser was fed with additional data (it should only be given the bytes within a section,
    /// not the whole advertisement), or the length field in the header of the data element is
    /// malformed.
    UnexpectedDataAfterEnd,
    /// The data elements were not in strictly increasing order by type code.
    TypeCodesOutOfOrder,
    /// A parse error is returned during nom.
    NomError(error::ErrorKind),
}

impl fmt::Display for DataElementParseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            DataElementParseError::UnexpectedDataAfterEnd => write!(f, "Unexpected data after end"),
            DataElementParseError::TypeCodesOutOfOrder => {
                write!(f, "DE type codes are out of order")
            }
            DataElementParseError::NomError(_) => write!(f, "Nom error"),
        }
    }
}

#[cfg(feature = "std")]
impl std::error::Error for DataElementParseError {}

/// A reduced-information variant of a [`DataElement`] that completely
/// lacks the ability to derive DE salts due to not tracking its offset
/// within a section (in any form).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ProtoDataElement<'d> {
    /// The data element's type code.
    pub de_type: DeType,
    /// The raw bytes of this data element's payload.
    pub contents: &'d [u8],
}

impl ProtoDataElement<'_> {
    pub(crate) fn parse(input: &[u8]) -> nom::IResult<&[u8], ProtoDataElement<'_>> {
        let (remaining, header) = DeHeader::parse(input)?;
        let len = header.contents_len;
        let de_type = header.de_type;
        combinator::map(bytes::complete::take(len.as_u8()), move |contents| ProtoDataElement {
            de_type,
            contents,
        })
        .parse(remaining)
    }
}

/// Deserialize-specific version of a DE header that incorporates the
/// length of the subsequent DE contents (unlike the representation
/// in serialization, where the length is implicitly given as part
/// of the data-to-be-serialized).
#[derive(Debug, PartialEq, Eq, Clone)]
pub(crate) struct DeHeader {
    pub(crate) de_type: DeType,
    pub(crate) contents_len: DeLength,
}

impl DeHeader {
    pub(crate) fn parse(input: &[u8]) -> nom::IResult<&[u8], DeHeader> {
        // 1-byte header: 0b0LLLTTTT
        let parse_single_byte_de_header = combinator::map_res(
            combinator::verify(number::complete::u8, |&b| !deserialize::hi_bit_set(b)),
            |b| {
                // L bits
                let len = (b >> 4) & 0x07;
                // T bits
                let de_type = (b & 0x0F).into();

                len.try_into().map(|contents_len| DeHeader { contents_len, de_type })
            },
        );

        // multi-byte headers: 0b1LLLLLLL (0b1TTTTTTT)* 0b0TTTTTTT
        // leading 1 in first byte = multibyte format
        // leading 1 in subsequent bytes = there is at least 1 more type bytes
        // leading 0 = this is the last header byte
        // 127-bit length, effectively infinite type bit length

        // It's conceivable to have non-canonical extended type sequences where 1 or more leading
        // bytes don't have any bits set (other than the marker hi bit), thereby contributing nothing
        // to the final value.
        // To prevent that, we require that either there be only 1 type byte, or that the first of the
        // multiple type bytes must have a value bit set. It's OK to have no value bits in subsequent
        // type bytes.

        let parse_ext_de_header = combinator::verify(
            combinator::map_opt(
                sequence::pair(
                    // length byte w/ leading 1
                    combinator::map_res(
                        combinator::verify(number::complete::u8::<&[u8], _>, |&b| {
                            deserialize::hi_bit_set(b)
                        }),
                        // snag the lower 7 bits
                        |b| (b & 0x7F).try_into(),
                    ),
                    branch::alt((
                        // 1 type byte case
                        combinator::recognize(
                            // 0-hi-bit type code byte
                            combinator::verify(number::complete::u8, |&b| {
                                !deserialize::hi_bit_set(b)
                            }),
                        ),
                        // multiple type byte case: leading type byte must have at least 1 value bit
                        combinator::recognize((
                            // hi bit and at least 1 value bit, otherwise it would be non-canonical
                            combinator::verify(number::complete::u8, |&b| {
                                deserialize::hi_bit_set(b) && (b & 0x7F != 0)
                            }),
                            // 0-3 1-hi-bit type code bytes with any bit pattern. Max is 3 since two 7
                            // bit type chunks are processed before and after this, for a total of 5,
                            // and that's as many 7-bit chunks as are needed to support a 32-bit type.
                            bytes::complete::take_while_m_n(0, 3, deserialize::hi_bit_set),
                            // final 0-hi-bit type code byte
                            combinator::verify(number::complete::u8, |&b| {
                                !deserialize::hi_bit_set(b)
                            }),
                        )),
                    )),
                ),
                |(contents_len, type_bytes)| {
                    // snag the low 7 bits of each type byte and accumulate
                    type_bytes
                        .iter()
                        .try_fold(0_u64, |accum, b| {
                            accum.checked_shl(7).map(|n| n + ((b & 0x7F) as u64))
                        })
                        .and_then(|type_code| u32::try_from(type_code).ok())
                        .and_then(|type_code| DeType::try_from(type_code).ok())
                        .map(|de_type| DeHeader { contents_len, de_type })
                },
            ),
            |header| {
                // verify that the length and type code actually require use of the extended bit
                de_requires_extended_bit(header.de_type.as_u32(), header.contents_len.len)
            },
        );

        branch::alt((parse_single_byte_de_header, parse_ext_de_header)).parse(input)
    }
}
