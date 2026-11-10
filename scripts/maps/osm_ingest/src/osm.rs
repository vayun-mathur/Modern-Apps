//! Typed iteration over a `PrimitiveBlock`: dense nodes, plain nodes, ways and
//! relations, each with string-table-resolved tag lookup.
//!
//! This is the layer that replaces libosmium's `buf.select<osmium::Node>()` /
//! `way.tags().get_value_by_key(...)` API surface. Views borrow from the
//! inflated block and from short-lived scratch buffers, so a whole blob is
//! walked with a handful of allocations.

use crate::pbf::{PrimitiveBlock, KIND_NODES, KIND_RELATIONS, KIND_WAYS};
use crate::proto::{self, Reader, Result, WIRE_BYTES, WIRE_VARINT};

/// Relation member types, matching `Relation.MemberType` in the PBF schema.
pub const MEMBER_NODE: u8 = 0;
pub const MEMBER_WAY: u8 = 1;
pub const MEMBER_RELATION: u8 = 2;

enum TagLayout<'b> {
    /// `Node`/`Way`/`Relation`: parallel key and value string-table indices.
    Split { keys: &'b [u32], vals: &'b [u32] },
    /// `DenseNodes.keys_vals`: `k, v, k, v, ...` for one node.
    Interleaved(&'b [u32]),
}

/// One element's tag set, resolved lazily against the block string table.
pub struct Tags<'b, 'a> {
    block: &'b PrimitiveBlock<'a>,
    layout: TagLayout<'b>,
}

impl<'b, 'a> Tags<'b, 'a> {
    pub fn get(&self, key: &str) -> Option<&'a [u8]> {
        let want = key.as_bytes();
        match self.layout {
            TagLayout::Split { keys, vals } => keys
                .iter()
                .zip(vals)
                .find(|(k, _)| self.block.string(**k) == want)
                .map(|(_, v)| self.block.string(*v)),
            TagLayout::Interleaved(kv) => kv
                .chunks_exact(2)
                .find(|p| self.block.string(p[0]) == want)
                .map(|p| self.block.string(p[1])),
        }
    }

    /// Tag value as UTF-8. OSM guarantees UTF-8; a non-conforming value is
    /// treated as absent rather than silently mangled.
    pub fn get_str(&self, key: &str) -> Option<&'a str> {
        std::str::from_utf8(self.get(key)?).ok()
    }

    pub fn iter(&self) -> impl Iterator<Item = (&'a [u8], &'a [u8])> + '_ {
        let split = match self.layout {
            TagLayout::Split { keys, vals } => Some(keys.iter().copied().zip(vals.iter().copied())),
            TagLayout::Interleaved(_) => None,
        };
        let inter = match self.layout {
            TagLayout::Interleaved(kv) => Some(kv.chunks_exact(2).map(|p| (p[0], p[1]))),
            TagLayout::Split { .. } => None,
        };
        split
            .into_iter()
            .flatten()
            .chain(inter.into_iter().flatten())
            .map(|(k, v)| (self.block.string(k), self.block.string(v)))
    }

    pub fn is_empty(&self) -> bool {
        match self.layout {
            TagLayout::Split { keys, .. } => keys.is_empty(),
            TagLayout::Interleaved(kv) => kv.is_empty(),
        }
    }
}

pub struct NodeView<'b, 'a> {
    pub id: i64,
    pub lat_e7: i32,
    pub lon_e7: i32,
    pub tags: Tags<'b, 'a>,
}

pub struct WayView<'b, 'a> {
    pub id: i64,
    pub tags: Tags<'b, 'a>,
    /// Absolute node ids (the on-disk deltas are already accumulated).
    pub refs: &'b [i64],
}

pub struct Member<'a> {
    pub id: i64,
    pub role: &'a [u8],
    pub kind: u8,
}

pub struct RelationView<'b, 'a> {
    pub id: i64,
    pub tags: Tags<'b, 'a>,
    pub members: &'b [Member<'a>],
}

pub enum Element<'b, 'a> {
    Node(NodeView<'b, 'a>),
    Way(WayView<'b, 'a>),
    Relation(RelationView<'b, 'a>),
}

/// Walk every element of `block` whose kind is in `want`, calling `f`.
///
/// `kinds` accumulates the kinds the block *contains* (regardless of `want`), so
/// a first full pass can record a per-blob mask and later passes can skip blobs
/// outright.
pub fn visit_block<F>(
    block: &PrimitiveBlock,
    want: u8,
    kinds: &mut u8,
    f: &mut F,
) -> Result<()>
where
    F: FnMut(Element) -> Result<()>,
{
    // Reuse this thread's decode buffers across blocks. A fresh `Scratch` per
    // block regrew ~10 vectors from empty to tens of KB on every blob, and with
    // 192 decode threads that malloc/realloc churn serialised on allocator
    // locks (most of a California build's ~400 s of system time). Contents are
    // cleared before every use, so reuse changes nothing but the allocations.
    // Taken, not borrowed, so a nested visit (none exists) would just get a
    // fresh one.
    let mut scratch = SCRATCH.with(|c| c.take());
    let r = visit_groups(block, want, kinds, f, &mut scratch);
    SCRATCH.with(|c| c.set(scratch));
    r
}

thread_local! {
    static SCRATCH: std::cell::Cell<Scratch> = std::cell::Cell::new(Scratch::default());
}

fn visit_groups<F>(
    block: &PrimitiveBlock,
    want: u8,
    kinds: &mut u8,
    f: &mut F,
    scratch: &mut Scratch,
) -> Result<()>
where
    F: FnMut(Element) -> Result<()>,
{
    for group in &block.groups {
        let mut g = Reader::new(group);
        while let Some((field, wire)) = g.next_field()? {
            if wire != WIRE_BYTES {
                g.skip(wire)?;
                continue;
            }
            match field {
                1 => {
                    *kinds |= KIND_NODES;
                    let body = g.bytes()?;
                    if want & KIND_NODES != 0 {
                        visit_node(block, body, scratch, f)?;
                    }
                }
                2 => {
                    *kinds |= KIND_NODES;
                    let body = g.bytes()?;
                    if want & KIND_NODES != 0 {
                        visit_dense(block, body, scratch, f)?;
                    }
                }
                3 => {
                    *kinds |= KIND_WAYS;
                    let body = g.bytes()?;
                    if want & KIND_WAYS != 0 {
                        visit_way(block, body, scratch, f)?;
                    }
                }
                4 => {
                    *kinds |= KIND_RELATIONS;
                    let body = g.bytes()?;
                    if want & KIND_RELATIONS != 0 {
                        visit_relation(block, body, scratch, f)?;
                    }
                }
                _ => g.skip(wire)?,
            }
        }
    }
    Ok(())
}

#[derive(Default)]
struct Scratch {
    keys: Vec<u32>,
    vals: Vec<u32>,
    refs: Vec<i64>,
    ids: Vec<i64>,
    lats: Vec<i64>,
    lons: Vec<i64>,
    kv: Vec<u32>,
    roles: Vec<u32>,
    memids: Vec<i64>,
    types: Vec<u32>,
}

fn visit_node<F>(block: &PrimitiveBlock, body: &[u8], s: &mut Scratch, f: &mut F) -> Result<()>
where
    F: FnMut(Element) -> Result<()>,
{
    s.keys.clear();
    s.vals.clear();
    let mut id = 0i64;
    let mut lat = 0i64;
    let mut lon = 0i64;
    let mut r = Reader::new(body);
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_VARINT) => id = r.svarint()?,
            (2, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut s.keys)?,
            (3, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut s.vals)?,
            (8, WIRE_VARINT) => lat = r.svarint()?,
            (9, WIRE_VARINT) => lon = r.svarint()?,
            _ => r.skip(wire)?,
        }
    }
    let n = s.keys.len().min(s.vals.len());
    f(Element::Node(NodeView {
        id,
        lat_e7: block.lat_e7(lat),
        lon_e7: block.lon_e7(lon),
        tags: Tags {
            block,
            layout: TagLayout::Split {
                keys: &s.keys[..n],
                vals: &s.vals[..n],
            },
        },
    }))
}

fn visit_dense<F>(block: &PrimitiveBlock, body: &[u8], s: &mut Scratch, f: &mut F) -> Result<()>
where
    F: FnMut(Element) -> Result<()>,
{
    s.ids.clear();
    s.lats.clear();
    s.lons.clear();
    s.kv.clear();
    let mut r = Reader::new(body);
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_BYTES) => proto::packed_delta(r.bytes()?, &mut s.ids)?,
            (8, WIRE_BYTES) => proto::packed_delta(r.bytes()?, &mut s.lats)?,
            (9, WIRE_BYTES) => proto::packed_delta(r.bytes()?, &mut s.lons)?,
            (10, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut s.kv)?,
            _ => r.skip(wire)?,
        }
    }
    if s.lats.len() < s.ids.len() || s.lons.len() < s.ids.len() {
        return proto::err("DenseNodes has fewer coordinates than ids");
    }

    // keys_vals is one flat 0-terminated k,v run per node, in node order. An
    // absent keys_vals means no node in the block carries tags.
    let mut cursor = 0usize;
    for i in 0..s.ids.len() {
        let start = cursor;
        while cursor < s.kv.len() && s.kv[cursor] != 0 {
            cursor += 2;
        }
        if cursor > s.kv.len() {
            return proto::err("DenseNodes keys_vals is truncated");
        }
        let end = cursor;
        if cursor < s.kv.len() {
            cursor += 1; // step over the 0 terminator
        }
        f(Element::Node(NodeView {
            id: s.ids[i],
            lat_e7: block.lat_e7(s.lats[i]),
            lon_e7: block.lon_e7(s.lons[i]),
            tags: Tags {
                block,
                layout: TagLayout::Interleaved(&s.kv[start..end]),
            },
        }))?;
    }
    Ok(())
}

fn visit_way<F>(block: &PrimitiveBlock, body: &[u8], s: &mut Scratch, f: &mut F) -> Result<()>
where
    F: FnMut(Element) -> Result<()>,
{
    s.keys.clear();
    s.vals.clear();
    s.refs.clear();
    let mut id = 0i64;
    let mut r = Reader::new(body);
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_VARINT) => id = r.ivarint()?,
            (2, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut s.keys)?,
            (3, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut s.vals)?,
            (8, WIRE_BYTES) => proto::packed_delta(r.bytes()?, &mut s.refs)?,
            _ => r.skip(wire)?,
        }
    }
    let n = s.keys.len().min(s.vals.len());
    f(Element::Way(WayView {
        id,
        tags: Tags {
            block,
            layout: TagLayout::Split {
                keys: &s.keys[..n],
                vals: &s.vals[..n],
            },
        },
        refs: &s.refs,
    }))
}

fn visit_relation<F>(block: &PrimitiveBlock, body: &[u8], s: &mut Scratch, f: &mut F) -> Result<()>
where
    F: FnMut(Element) -> Result<()>,
{
    s.keys.clear();
    s.vals.clear();
    s.roles.clear();
    s.memids.clear();
    s.types.clear();
    let mut id = 0i64;
    let mut r = Reader::new(body);
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_VARINT) => id = r.ivarint()?,
            (2, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut s.keys)?,
            (3, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut s.vals)?,
            (8, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut s.roles)?,
            (9, WIRE_BYTES) => proto::packed_delta(r.bytes()?, &mut s.memids)?,
            (10, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut s.types)?,
            _ => r.skip(wire)?,
        }
    }
    let count = s.memids.len().min(s.types.len()).min(s.roles.len());
    let members: Vec<Member> = (0..count)
        .map(|i| Member {
            id: s.memids[i],
            role: block.string(s.roles[i]),
            kind: s.types[i] as u8,
        })
        .collect();
    let n = s.keys.len().min(s.vals.len());
    f(Element::Relation(RelationView {
        id,
        tags: Tags {
            block,
            layout: TagLayout::Split {
                keys: &s.keys[..n],
                vals: &s.vals[..n],
            },
        },
        members: &members,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pbf;
    use crate::testpbf;

    /// `(id, lat_e7, lon_e7, tags)`, `(id, refs)`, `(id, [(member id, role, kind)])`.
    type Decoded = (
        Vec<(i64, i32, i32, Vec<(String, String)>)>,
        Vec<(i64, Vec<i64>)>,
        Vec<(i64, Vec<(i64, String, u8)>)>,
    );

    fn walk(want: u8) -> Decoded {
        let mut blob = Vec::new();
        pbf::inflate_blob(&testpbf::sample_data_blob(), &mut blob).unwrap();
        let block = pbf::PrimitiveBlock::decode(&blob).unwrap();
        let mut nodes = Vec::new();
        let mut ways = Vec::new();
        let mut rels = Vec::new();
        let mut kinds = 0;
        visit_block(&block, want, &mut kinds, &mut |el| {
            match el {
                Element::Node(n) => nodes.push((
                    n.id,
                    n.lat_e7,
                    n.lon_e7,
                    n.tags
                        .iter()
                        .map(|(k, v)| {
                            (
                                String::from_utf8_lossy(k).into_owned(),
                                String::from_utf8_lossy(v).into_owned(),
                            )
                        })
                        .collect(),
                )),
                Element::Way(w) => ways.push((w.id, w.refs.to_vec())),
                Element::Relation(r) => rels.push((
                    r.id,
                    r.members
                        .iter()
                        .map(|m| (m.id, String::from_utf8_lossy(m.role).into_owned(), m.kind))
                        .collect(),
                )),
            }
            Ok(())
        })
        .unwrap();
        assert_eq!(kinds, KIND_NODES | KIND_WAYS | KIND_RELATIONS);
        (nodes, ways, rels)
    }

    #[test]
    fn decodes_dense_nodes_with_per_node_tags() {
        let (nodes, _, _) = walk(KIND_NODES);
        assert_eq!(nodes.len(), testpbf::NODE_COUNT);
        // Ids are delta-encoded; check they accumulated.
        assert_eq!(nodes[0].0, 1);
        assert_eq!(nodes[1].0, 2);
        // Coordinates come back as the exact 1e-7 integers.
        assert_eq!((nodes[0].1, nodes[0].2), (370_000_000, -1_220_000_000));
        // The bus stop and the cafe are the only tagged nodes in the sample.
        let stop = nodes.iter().find(|n| n.0 == testpbf::STOP_NODE_ID).unwrap();
        assert!(stop
            .3
            .iter()
            .any(|(k, v)| k == "highway" && v == "bus_stop"));
        assert!(stop.3.iter().any(|(k, v)| k == "name" && v == "Test Stop"));
        // Untagged neighbours really have no tags (the 0 terminator is honoured).
        assert!(nodes.iter().find(|n| n.0 == 1).unwrap().3.is_empty());
    }

    #[test]
    fn decodes_way_refs_as_absolute_ids() {
        let (_, ways, _) = walk(KIND_WAYS);
        assert_eq!(ways.len(), 3);
        assert_eq!(ways[0], (testpbf::MAIN_WAY_ID, vec![1, 2, 3, 4]));
        assert_eq!(ways[1], (testpbf::SERVICE_WAY_ID, vec![4, 2]));
        assert_eq!(ways[2], (testpbf::AREA_WAY_ID, vec![1, 2, 3, 4, 1]));
    }

    #[test]
    fn decodes_relation_members_with_roles() {
        let (_, _, rels) = walk(KIND_RELATIONS);
        assert_eq!(rels.len(), 1);
        let (id, members) = &rels[0];
        assert_eq!(*id, testpbf::RELATION_ID);
        assert_eq!(members.len(), 1);
        assert_eq!(members[0].0, testpbf::AREA_WAY_ID);
        assert_eq!(members[0].1, "outer");
        assert_eq!(members[0].2, MEMBER_WAY);
    }

    #[test]
    fn want_mask_skips_unwanted_kinds_but_still_reports_them() {
        let (nodes, ways, rels) = walk(KIND_WAYS);
        assert!(nodes.is_empty());
        assert!(rels.is_empty());
        assert_eq!(ways.len(), 3);
    }
}
