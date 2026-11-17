// VPN core packet — trimmed version of gotatun packet/mod.rs.
// Owned zero-copy Packet<T> wrapper plus IP/WG trait glue.

use std::{fmt::{self, Debug}, marker::PhantomData, ops::{Deref, DerefMut}};
use bytes::{Buf, BytesMut};
use either::Either;
use eyre::bail;
use zerocopy::{FromBytes, Immutable, IntoBytes, KnownLayout, Unaligned};

mod decode; mod ip; mod ipv4; mod ipv6; mod pool; mod tcp; mod udp; mod util; mod wg;
pub use decode::*; pub use ip::*; pub use ipv4::*; pub use ipv6::*; pub use pool::*;
#[cfg(test)] mod wg_tests;
#[cfg(test)] mod udp_tests;
#[cfg(test)] mod util_tests;
#[cfg(test)] mod ip_tests;
#[cfg(test)] mod ipvx_extra_tests;
pub use tcp::*; pub use udp::*; pub use util::*; pub use wg::*;

pub struct Packet<Kind: ?Sized = [u8]> {
    inner: PacketInner,
    _kind: PhantomData<Kind>,
}

struct PacketInner {
    buf: BytesMut,
    _return_to_pool: Option<ReturnToPool>,
}

pub trait PoD: FromBytes + IntoBytes + KnownLayout + Immutable + Unaligned {}
impl<T: FromBytes + IntoBytes + KnownLayout + Immutable + Unaligned + ?Sized> PoD for T {}

impl<T: IntoBytes + KnownLayout + Immutable + ?Sized> Packet<T> {
    fn cast<Y: FromBytes + KnownLayout + Immutable + ?Sized>(self) -> Packet<Y> {
        Packet { inner: self.inner, _kind: PhantomData::<Y> }
    }
}

impl<T: IntoBytes + KnownLayout + Immutable + ?Sized> Packet<T> {
    pub fn into_bytes(self) -> Packet<[u8]> { self.cast() }
    pub fn as_bytes(&self) -> &[u8] { &self.inner.buf }
    pub fn len(&self) -> usize { self.inner.buf.len() }
    pub fn is_empty(&self) -> bool { self.inner.buf.is_empty() }
    pub fn as_ptr(&self) -> *const u8 { self.inner.buf.as_ptr() }
}

impl<T: IntoBytes + FromBytes + KnownLayout + Immutable + ?Sized> Packet<T> {
    pub fn copy_from(payload: &T) -> Self {
        Self { inner: PacketInner { buf: BytesMut::from(payload.as_bytes()), _return_to_pool: None }, _kind: PhantomData }
    }
    pub fn overwrite_with<Y: IntoBytes + FromBytes + KnownLayout + Immutable + ?Sized>(
        mut self, payload: &Y,
    ) -> Packet<Y> {
        self.inner.buf.clear();
        self.inner.buf.extend_from_slice(payload.as_bytes());
        self.cast()
    }
}

impl AsRef<[u8]> for Packet<[u8]> {
    fn as_ref(&self) -> &[u8] { self.inner.buf.as_ref() }
}

impl From<Packet<Ipv4<Udp>>> for Packet<Ipv4<[u8]>> { fn from(v: Packet<Ipv4<Udp>>) -> Self { v.cast() } }
impl From<Packet<Ipv6<Udp>>> for Packet<Ipv6<[u8]>> { fn from(v: Packet<Ipv6<Udp>>) -> Self { v.cast() } }
impl From<Packet<Ipv4<Tcp>>> for Packet<Ipv4<[u8]>> { fn from(v: Packet<Ipv4<Tcp>>) -> Self { v.cast() } }
impl From<Packet<Ipv6<Tcp>>> for Packet<Ipv6<[u8]>> { fn from(v: Packet<Ipv6<Tcp>>) -> Self { v.cast() } }
impl From<Packet<Ipv4<Udp>>> for Packet<Ip> { fn from(v: Packet<Ipv4<Udp>>) -> Self { v.cast() } }
impl From<Packet<Ipv6<Udp>>> for Packet<Ip> { fn from(v: Packet<Ipv6<Udp>>) -> Self { v.cast() } }
impl From<Packet<Ipv4<Tcp>>> for Packet<Ip> { fn from(v: Packet<Ipv4<Tcp>>) -> Self { v.cast() } }
impl From<Packet<Ipv6<Tcp>>> for Packet<Ip> { fn from(v: Packet<Ipv6<Tcp>>) -> Self { v.cast() } }
impl From<Packet<Ipv4<[u8]>>> for Packet<Ip> { fn from(v: Packet<Ipv4<[u8]>>) -> Self { v.cast() } }
impl From<Packet<Ipv6<[u8]>>> for Packet<Ip> { fn from(v: Packet<Ipv6<[u8]>>) -> Self { v.cast() } }
impl From<Packet<Ipv4<Udp>>> for Packet<[u8]> { fn from(v: Packet<Ipv4<Udp>>) -> Self { v.cast() } }
impl From<Packet<Ipv6<Udp>>> for Packet<[u8]> { fn from(v: Packet<Ipv6<Udp>>) -> Self { v.cast() } }
impl From<Packet<Ipv4<Tcp>>> for Packet<[u8]> { fn from(v: Packet<Ipv4<Tcp>>) -> Self { v.cast() } }
impl From<Packet<Ipv6<Tcp>>> for Packet<[u8]> { fn from(v: Packet<Ipv6<Tcp>>) -> Self { v.cast() } }
impl From<Packet<Ipv4<[u8]>>> for Packet<[u8]> { fn from(v: Packet<Ipv4<[u8]>>) -> Self { v.cast() } }
impl From<Packet<Ipv6<[u8]>>> for Packet<[u8]> { fn from(v: Packet<Ipv6<[u8]>>) -> Self { v.cast() } }
impl From<Packet<Ip>> for Packet<[u8]> { fn from(v: Packet<Ip>) -> Self { v.cast() } }
impl From<Packet<WgData>> for Packet<[u8]> { fn from(v: Packet<WgData>) -> Self { v.cast() } }

impl<P: IntoBytes + KnownLayout + Immutable + Unaligned> From<Packet<P>> for Packet<[u8]> {
    fn from(value: Packet<P>) -> Packet<[u8]> { value.cast() }
}

impl Default for Packet<[u8]> {
    fn default() -> Self {
        Self { inner: PacketInner { buf: BytesMut::default(), _return_to_pool: None }, _kind: PhantomData }
    }
}

impl Packet<[u8]> {
    pub fn new_from_pool(return_to_pool: ReturnToPool, bytes: BytesMut) -> Self {
        Self { inner: PacketInner { buf: bytes, _return_to_pool: Some(return_to_pool) }, _kind: PhantomData::<[u8]> }
    }
    pub fn from_bytes(bytes: BytesMut) -> Self {
        Self { inner: PacketInner { buf: bytes, _return_to_pool: None }, _kind: PhantomData::<[u8]> }
    }
    pub fn truncate(&mut self, new_len: usize) { self.inner.buf.truncate(new_len); }
    pub fn buf_mut(&mut self) -> &mut BytesMut { &mut self.inner.buf }
    pub fn buf(&self) -> &[u8] { &self.inner.buf }
    pub fn try_into_ip(self) -> eyre::Result<Packet<Ip>> {
        let decoder = IpDecoder { version: false, min_length: true };
        let packet = decoder.decode_owned(self)?;
        Ok(packet)
    }
    pub fn try_into_ipvx(self) -> eyre::Result<Either<Packet<Ipv4>, Packet<Ipv6>>> {
        self.try_into_ip()?.try_into_ipvx()
    }
}

impl Packet<Ip> {
    pub fn try_into_ipvx(self) -> eyre::Result<Either<Packet<Ipv4>, Packet<Ipv6>>> {
        match self.header.version() {
            4 => {
                let decoder = Ipv4Decoder { checksum: false, version: false, ..Ipv4Decoder::CHECK_ALL };
                decoder.decode_owned(self).map(Either::Left)
            }
            6 => {
                let decoder = Ipv6Decoder { version: false, ..Ipv6Decoder::CHECK_ALL };
                decoder.decode_owned(self).map(Either::Right)
            }
            v => bail!("Bad IP version: {v}"),
        }
        .map_err(Into::into)
    }
}

impl<T: PoD + ?Sized> Packet<Ipv4<T>> {
    pub fn into_payload(mut self) -> Packet<T> {
        self.inner.buf.advance(Ipv4Header::LEN);
        self.cast::<T>()
    }
}
impl<T: PoD + ?Sized> Packet<Ipv6<T>> {
    pub fn into_payload(mut self) -> Packet<T> {
        self.inner.buf.advance(Ipv6Header::LEN);
        self.cast::<T>()
    }
}
impl<T: PoD + ?Sized> Packet<Udp<T>> {
    pub fn into_payload(mut self) -> Packet<T> {
        self.inner.buf.advance(UdpHeader::LEN);
        self.cast::<T>()
    }
}

impl<Kind> Deref for Packet<Kind> where Kind: FromBytes + KnownLayout + Immutable + Unaligned + ?Sized {
    type Target = Kind;
    fn deref(&self) -> &Self::Target {
        Self::Target::ref_from_bytes(&self.inner.buf).expect("checked payload")
    }
}
impl<Kind> DerefMut for Packet<Kind> where Kind: PoD + ?Sized {
    fn deref_mut(&mut self) -> &mut Self::Target {
        Self::Target::mut_from_bytes(&mut self.inner.buf).expect("checked payload")
    }
}

#[cfg(test)]
impl<Kind: ?Sized> Clone for Packet<Kind> {
    fn clone(&self) -> Self {
        Self { inner: PacketInner { buf: self.inner.buf.clone(), _return_to_pool: None }, _kind: PhantomData }
    }
}

impl<Kind: Debug> Debug for Packet<Kind> where Kind: PoD + ?Sized {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_tuple("Packet").field(&self.deref()).finish()
    }
}
