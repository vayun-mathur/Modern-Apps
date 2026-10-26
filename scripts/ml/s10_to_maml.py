#!/usr/bin/env python3
"""S10-sourced control conversion: transcribe Section 10's OWN transformer
weights (same layout/norms/scales the golden ran) into a MAML, to decide
whether the parity failure is in the ARTISAN weight decode or the runtime.

Also emits the S10 embed file: S2-dump working table + S10 head table
(t2698, raw-scale `embedder.decode` composite) + shared projection/norm
from S10's own tensors + 35 per-layer mmap tables. The head table is the
piece the artisan bundle cannot supply (its 2-bit embedder packing is a
different checkpoint's arrangement); the tied head reads it raw-scale
(see nets::gemma4::EMBED_GAIN).

Usage:
  GEMMA4_EMBED_DUMP=analysis/maml-rebuild/embed_s2_fp32.bin \
    python scripts/ml/s10_to_maml.py -o build/gemma4-s10 --print-digest
  (--text-only writes gemma4_text.maml alone and needs no dump.)

Slim embed (the shipped file):
  python scripts/ml/s10_to_maml.py -o build/gemma4-release \
    --slim-embed build/gemma4/gemma4_embed.maml
  copies the token table triple (0-2) and the 35 per-layer int4 tables
  (7+i*3) byte-for-byte out of a full embed file, with no S10 read and no
  re-quantisation. SHARED_PROJ/norm (3-6) become four one-element fp16
  placeholders so every index the runtime reads holds (nets::gemma4::embed::
  SLIM_TENSORS, 112 tensors): the per-layer combine reads the text file's
  copy (the 1094-tensor gemma4_text.maml, nets::gemma4::COMBINE_PROJ), and
  the head table + both head-chunk copies are dropped because
  gemma4_head.maml carries the head. ~1.4 GB instead of 3.6 GB.
Then:
  cargo run --offline --release -p modelrunner --example check_gemma4_parity -- \
    build/gemma4-s10/gemma4_text.maml build/gemma4-s10/gemma4_embed.maml \
    build/gemma4/logits_golden.json build/gemma4/trace.json

PASS => artisan payloads are the problem (port S10's tables).
FAIL => the runtime/net is the problem (same reading convention both sides).
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import maml_convert as mc
from litertlm_to_maml import emit4, emit8, emit_vec

import tflite
from tflite.Model import Model

S10 = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'analysis',
                   'base-sections', 'Section10_TFLiteModel_tf_lite_prefill_decode.tflite')

# Base-bundle Section 3 (per-layer embedder). Its 35 composite tables
# (tensors 10..44 -> buffers 11..45, [262144,256] signed nibbles + 1 F32
# scale/row, zp all 0) decode cos 1.000000 vs the live S3 signature runner,
# so they are the exact source the golden uses. Preferred over the artisan
# file when present (the artisan tables are a different checkpoint's
# arrangement: global XOR-2 code multisets, ~0.30 position-wise agreement).
S3 = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'analysis',
                  'base-sections', 'Section3_TFLiteModel_tf_lite_per_layer_embedder.tflite')

# GPU tied-head chunking (2026-09-26): HEAD_SPLITS rank-4 fp16 chunks of the
# head table, each small enough to fit one Vulkan segment window (~96 MiB at
# guaranteed 128 MiB maxStorageBufferRange). 16 x 16384 x 1536 x 2B ~= 50 MB
# per chunk. Overridable via GEMMA4_HEAD_SPLITS for devices whose
# maxStorageBufferRange queries smaller (raise the count, shrink the chunk).
HEAD_SPLITS = int(os.environ.get('GEMMA4_HEAD_SPLITS', '16'))


def m_OperatorCode(sg, o):
    raise NotImplementedError


class S10Reader:
    """Section-10 tensor access with TFLite-native int4 reading."""

    def __init__(self, path):
        with open(path, 'rb') as f:
            self.sec = f.read()
        self.model = Model.GetRootAs(bytearray(self.sec), 0)
        self.sg = self.model.Subgraphs(0)
        self.tensors = {}
        for ti in range(self.sg.TensorsLength()):
            t = self.sg.Tensors(ti)
            self.tensors[t.Name().decode('utf-8')] = ti

    def const(self, ti):
        t = self.sg.Tensors(ti)
        b = self.model.Buffers(t.Buffer())
        return bytes(b.DataAsNumpy())

    def f32(self, ti):
        return np.frombuffer(self.const(ti), dtype=np.float32).copy()

    def dequant4(self, ti, rows, cols):
        t = self.sg.Tensors(ti)
        raw = np.frombuffer(self.const(ti), dtype=np.uint8)
        assert raw.size * 2 >= rows * cols, (ti, raw.size, rows, cols)
        lo = (raw & 0xF).astype(np.float32)
        hi = ((raw >> 4) & 0xF).astype(np.float32)
        flat = np.empty(raw.size * 2, dtype=np.float32)
        flat[0::2] = lo
        flat[1::2] = hi
        flat = np.where(flat >= 8, flat - 16, flat).reshape(rows, cols)
        q = t.Quantization()
        s = np.array([q.Scale(i) for i in range(q.ScaleLength())], dtype=np.float32)
        assert s.size == rows, (ti, s.size, rows)
        out = flat * s[:, None]
        m = float(np.abs(out).mean())
        if not (1e-6 < m < 10.0):
            print(f'WARN t{ti}: mean abs {m:.2e}')
        return out

    def dequant2(self, ti, rows, cols):
        """2-bit table with the head codebook ([0,+s,-2s,-s]) -> fp32."""
        t = self.sg.Tensors(ti)
        raw = np.frombuffer(self.const(ti), dtype=np.uint8)
        assert raw.size * 4 >= rows * cols, (ti, raw.size, rows, cols)
        v = np.empty(raw.size * 4, dtype=np.float64)
        for k in range(4):
            v[k::4] = ((raw >> (2 * k)) & 0x3).astype(np.float64)
        v = v[:rows * cols].reshape(rows, cols)
        codebook = np.array([0.0, 1.0, -2.0, -1.0])
        v = codebook[v.astype(int)]
        q = t.Quantization()
        s = np.array([q.Scale(i) for i in range(q.ScaleLength())], dtype=np.float32)
        assert s.size == rows, (ti, s.size, rows)
        return (v * s[:, None]).astype(np.float32)

    def dequant8(self, ti, rows, cols):
        t = self.sg.Tensors(ti)
        raw = np.frombuffer(self.const(ti), dtype=np.int8).astype(np.float32)
        raw = raw[:rows * cols].reshape(rows, cols)
        q = t.Quantization()
        s = np.array([q.Scale(i) for i in range(q.ScaleLength())], dtype=np.float32)
        assert s.size == rows, (ti, s.size, rows)
        return raw * s[:, None]


class S3Reader:
    """Section-3 per-layer tables (tensors 10..44 -> buffers 11..45).

    Each composite is [262144,256] signed nibbles (lo-first) with 1 F32
    scale/row (zp all 0): table[i] = nibbles * scales. Proven cos 1.000000
    vs the live S3 `per_layer_embedder` runner on sampled tokens.
    """

    def __init__(self, path):
        with open(path, 'rb') as f:
            self.sec = f.read()
        self.model = Model.GetRootAs(bytearray(self.sec), 0)
        self.sg = self.model.Subgraphs(0)

    def table(self, index):
        ti = 10 + index
        t = self.sg.Tensors(ti)
        b = self.model.Buffers(t.Buffer())
        raw = np.frombuffer(bytes(b.DataAsNumpy()), dtype=np.uint8)
        assert raw.size >= 262144 * 128, (ti, raw.size)
        raw = raw[:262144 * 128]
        lo = (raw & 0xF).astype(np.float32)
        hi = ((raw >> 4) & 0xF).astype(np.float32)
        flat = np.empty(262144 * 256, dtype=np.float32)
        flat[0::2] = lo
        flat[1::2] = hi
        flat = np.where(flat >= 8, flat - 16, flat).reshape(262144, 256)
        q = t.Quantization()
        s = np.array([q.Scale(i) for i in range(q.ScaleLength())], dtype=np.float32)
        assert s.size == 262144, (ti, s.size)
        return flat * s[:, None]


# tensor ids mapped in collect_text (verified against the graph walk).
class T:
    # norms: (tensor id, len)
    FINAL = 37
    PRE = 303
    QN = 300
    KN = 299
    POSTA = 280
    PREFF = None  # filled per layer below
    POSTFF = 40
    POSTPL = 39
    SKIP = 276
    SHARED_NORM = None  # embed file (shared)


def fc_weight(rdr, layer, frag):
    """Find the FC weight tensor id for layer's fragment (the FC op itself)."""
    import re
    sg = rdr.sg
    pat = re.compile(rf'layer_{layer}/')
    for oi in range(sg.OperatorsLength()):
        o = sg.Operators(oi)
        if rdr.model.OperatorCodes(o.OpcodeIndex()).BuiltinCode() != 9:  # FULLY_CONNECTED
            continue
        outs = [o.Outputs(i) for i in range(o.OutputsLength())]
        if not outs:
            continue
        nm = sg.Tensors(outs[0]).Name().decode('utf-8')
        if not pat.search(nm) or frag not in nm:
            continue
        ins = [o.Inputs(i) for i in range(o.InputsLength())]
        for ii in ins:
            if ii < 0:
                continue
            t = sg.Tensors(ii)
            if t.ShapeLength() == 2:
                return ii
    raise KeyError((layer, frag))


def collect_text(rdr):
    layers, tensors = [], []
    fid = mc.Fidelity()
    D = 1536
    sg = rdr.sg
    # final norm + rotary tables. The angle tables are read off S10's own
    # `maybe_rope` div constants (NOT generated from theta: S10's full-layer
    # table has only 64 nonzero freqs of 256 — theta 1e6 over head_dim 512
    # for those, zeros after — and generating 256 freqs rotates 192 channel
    # pairs the reference leaves fixed, breaking every full layer's Q and K).
    # S10 tensor ids: 296 (sliding, 128 freqs), 245 (full, 256 entries).
    emit_vec(layers, tensors, 'transformer.final_norm.scale', rdr.f32(T.FINAL))
    for kind, dim, freq_ti in (('local', 256, 296), ('global', 512, 245)):
        freq = rdr.f32(freq_ti).astype(np.float64)
        assert freq.size == dim // 2, (kind, freq.size, dim)
        pos = np.arange(16384, dtype=np.float64)[:, None]
        ang = pos * freq[None, :]
        table = np.concatenate([np.cos(ang), np.sin(ang)], axis=1).astype(np.float32)
        tensors.append(table)
        layers.append(mc.Layer(len(layers), 'Rotary', f'{kind}_rotary_table',
                               f'Rotary t={list(table.shape)} S10-freq',
                               len(tensors) - 1, 1))
    # Shared all-ones vectors: the gamma of S10's parameter-free value_norm
    # (see nets::gemma4::ONE_SLIDING). Head-dim-wide: [256] and [512].
    emit_vec(layers, tensors, 'transformer.ones_sliding', np.ones(256, dtype=np.float32))
    emit_vec(layers, tensors, 'transformer.ones_full', np.ones(512, dtype=np.float32))
    # Track E proved the claimed runtime inverse gain does not exist for FF1
    # (hence FF1_SCALE = 1.0 below). The o and pp /4s were the same shape of
    # claim and equally uncompensated: no norm gamma in this file is
    # multiplied, and the runtime applies no inverse gain. At /4 the branches
    # ran at 1/4 trained magnitude on device; the post-branch norms erase the
    # scale but the MM/qint grids then quantize the wrong magnitudes.
    # (Proven at the o-tail: device o-quantize diverges, numpy
    # exact-arithmetic does not.)
    O_SCALE, FF1_SCALE, PL_SCALE = 1.0, 1.0, 1.0
    # NOTE (Track E, 2026-09-24): FF1_SCALE was 256.0 with a comment claiming
    # the runtime re-applies the inverse gain. That gain does not exist
    # anywhere in library/ml/src/main/rust/ (no such const; norm gammas match
    # S10 source exactly), so /256 silently ran every layer's up-path at 1/256
    # trained magnitude on device. Full-scale re-measured safe (worst 21k vs
    # fp16 max 65504).
    # NOTE (parity closure, 2026-09-25): O_SCALE and PL_SCALE were 4.0 with a
    # "Keep /4 on o/pp" note. Same finding as FF1: no compensating gain
    # exists, and the int8 grids quantize absolute magnitudes, so /4 flipped
    # codes at the o-tail. Both are 1.0 now; the quarantine record is in
    # build/gemma4-s10-fullfix/BLESSED.txt.
    # norm tensor ids per layer_0; other layers resolved by name scan
    for index in range(35):
        at = f'layer_{index}'
        full = index % 5 == 4
        dim = 512 if full else 256
        qrows = 8 * dim
        # locate per-layer norm GAMMAS: the composite outputs (e.g. 354) carry
        # no bytes; resolve each to its const gamma input (2nd composite input).
        import re
        pat = re.compile(rf'layer_{index}/')
        norms = {}
        for ti in range(sg.TensorsLength()):
            nm = sg.Tensors(ti).Name().decode('utf-8')
            if not pat.search(nm):
                continue
            for key, frag in [('pre', 'pre_attention_norm/composite'),
                              ('posta', 'post_attention_norm/composite'),
                              ('preff', 'pre_ffw_norm/composite'),
                              ('postff', 'post_ffw_norm/composite'),
                              ('postpl', 'post_per_layer_input_norm/composite'),
                              ('qn', 'query_norm/composite'),
                              ('kn', 'key_norm/composite')]:
                if frag in nm and key not in norms:
                    norms[key] = ti
        for key, ti in list(norms.items()):
            for oi in range(sg.OperatorsLength()):
                o = sg.Operators(oi)
                outs = [o.Outputs(i) for i in range(o.OutputsLength())]
                if ti not in outs:
                    continue
                ins = [o.Inputs(i) for i in range(o.InputsLength())]
                # composite ins: [activation, gamma]
                if len(ins) >= 2 and ins[1] >= 0:
                    norms[key] = ins[1]
        qw = fc_weight(rdr, index, 'q_einsum/reshape;')
        if index < 15:
            kw = fc_weight(rdr, index, 'k_einsum/reshape;')
            vw = fc_weight(rdr, index, 'v_einsum/reshape;')
        ow = fc_weight(rdr, index, 'attn_vec_einsum/btH')
        gw = fc_weight(rdr, index, 'gating_einsum1')
        uw = fc_weight(rdr, index, 'gating_einsum2')
        dw = fc_weight(rdr, index, 'mlp/linear')
        pew = fc_weight(rdr, index, 'per_layer_embedding_gate')
        pew2 = fc_weight(rdr, index, 'per_layer_embedding_projection')
        emit_vec(layers, tensors, f'transformer.layer_{index}.pre_attention_norm.scale',
                 rdr.f32(norms['pre']))
        emit_vec(layers, tensors, f'transformer.layer_{index}.attn.q_norm.scale',
                 rdr.f32(norms['qn']))
        emit4(fid, layers, tensors, f'transformer.layer_{index}.attn.q.w',
              rdr.dequant4(qw, qrows, D))
        if index < 15:
            emit_vec(layers, tensors, f'transformer.layer_{index}.attn.k_norm.scale',
                     rdr.f32(norms['kn']))
            emit4(fid, layers, tensors, f'transformer.layer_{index}.attn.k.w',
                  rdr.dequant4(kw, dim, D))
            emit4(fid, layers, tensors, f'transformer.layer_{index}.attn.v.w',
                  rdr.dequant4(vw, dim, D))
        emit4(fid, layers, tensors, f'transformer.layer_{index}.attn.o.w',
              rdr.dequant4(ow, D, qrows) / O_SCALE)
        emit_vec(layers, tensors, f'transformer.layer_{index}.post_attention_norm.scale',
                 rdr.f32(norms['posta']))
        emit_vec(layers, tensors, f'transformer.layer_{index}.pre_ffw_norm.scale',
                 rdr.f32(norms['preff']))
        if index < 15:
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.gate.w',
                  rdr.dequant4(gw, 6144, D))
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.ff1.w',
                  rdr.dequant4(uw, 6144, D) / FF1_SCALE)
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.linear.w',
                  rdr.dequant4(dw, D, 6144))
        else:
            # Slim layers: full-width 2-bit gate/up/down in S10's NATIVE code
            # space through the head codebook [0,+s,-2s,-s] lo-first (same as
            # t2698; S10Reader.dequant2 applies no XOR remap). The artisan
            # file's slim bytes are the same convention XOR-2-remapped
            # (exact global XOR-2 multisets, bit-identical scales) — that
            # remap lives in litertlm_to_maml.dequant2, NOT here. Gate and up
            # are separate 12288-wide tables; the runtime's ffn() is 12288
            # here, so no split.
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.gate.w',
                  rdr.dequant2(gw, 12288, D))
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.ff1.w',
                  rdr.dequant2(uw, 12288, D) / FF1_SCALE)
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.linear.w',
                  rdr.dequant2(dw, D, 12288))
        emit_vec(layers, tensors, f'transformer.layer_{index}.post_ffw_norm.scale',
                 rdr.f32(norms['postff']))
        emit8(fid, layers, tensors, f'transformer.layer_{index}.per_layer.gate.w',
              rdr.dequant8(pew, 256, 1536))
        emit8(fid, layers, tensors, f'transformer.layer_{index}.per_layer.proj.w',
              rdr.dequant8(pew2, 1536, 256) / PL_SCALE)
        emit_vec(layers, tensors, f'transformer.layer_{index}.post_per_layer_input_norm.scale',
                 rdr.f32(norms['postpl']))
        # skip: per-layer scalar via the skip-mul consumer chain (op ~100 for L0)
        layers_skip = None
        for oi in range(sg.OperatorsLength()):
            o = sg.Operators(oi)
            outs = [o.Outputs(i) for i in range(o.OutputsLength())]
            nm = sg.Tensors(outs[0]).Name().decode('utf-8') if outs else ''
            if f'layer_{index}.' in nm and '_maybe_apply_skip_scale/mul' in nm:
                ins = [o.Inputs(i) for i in range(o.InputsLength())]
                # the scalar input is the [1,1,1] one
                for ii in ins:
                    t = sg.Tensors(ii)
                    if t.ShapeLength() == 3:
                        layers_skip = rdr.f32(ii)[:1]
            if layers_skip is not None:
                break
        skip = np.ascontiguousarray(layers_skip, dtype=np.float32)
        tensors.append(skip)
        layers.append(mc.Layer(len(layers), 'MulScalar',
                               f'transformer.layer_{index}.skip.scale',
                               'MulScalar s=[1]', len(tensors) - 1, 1))
    # The per-layer combine's projection + norm, byte-identical to the embed
    # file's tensors 3-6, appended so the text plans combine in their own
    # submit (nets::gemma4::COMBINE_PROJ). Last, so every other index holds.
    emit_combine(fid, layers, tensors, rdr)
    fid.report(mc.MIN_INT4_COSINE)
    return layers, tensors


def emit_combine(fid, layers, tensors, rdr):
    """S10 t312 (int8 [8960,1536], 1 scale/row) + norm gamma t305 (256)."""
    emit8(fid, layers, tensors, 'transformer.embedder.per_layer_model_projection.w',
          rdr.dequant8(312, 8960, 1536))
    emit_vec(layers, tensors, 'transformer.embedder.per_layer_projection_norm.scale',
             rdr.f32(305))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-o', '--outdir', required=True)
    ap.add_argument('--print-digest', action='store_true')
    ap.add_argument('--text-only', action='store_true',
                    help='write gemma4_text.maml only (no embed dump needed)')
    ap.add_argument('--slim-embed', metavar='FULL_EMBED',
                    help='write only a slim gemma4_embed.maml copied from FULL_EMBED')
    args = ap.parse_args()
    os.makedirs(args.outdir, exist_ok=True)
    if args.slim_embed:
        write_slim_embed(args.slim_embed, os.path.join(args.outdir, 'gemma4_embed.maml'))
        return
    rdr = S10Reader(S10)
    layers, tensors = collect_text(rdr)
    digest = mc.layer_table_digest(layers)
    print(f's10_text: {len(layers)} layers, {len(tensors)} tensors, digest {digest}')
    import hashlib
    sha = hashlib.sha256(open(S10, 'rb').read()).digest()
    blob, _ = mc.build(layers, tensors, mc.GRAPHS['gemma4_text'], sha)
    out = os.path.join(args.outdir, 'gemma4_text.maml')
    open(out, 'wb').write(blob)
    print(f'wrote {out} ({len(blob)} bytes)')
    if args.text_only:
        return
    elayers, etensors = collect_embed(rdr)
    edigest = mc.layer_table_digest(elayers)
    print(f's10_embed: {len(elayers)} layers, {len(etensors)} tensors, digest {edigest}')
    eblob, _ = mc.build(elayers, etensors, mc.GRAPHS['gemma4_embed'], sha)
    eout = os.path.join(args.outdir, 'gemma4_embed.maml')
    open(eout, 'wb').write(eblob)
    print(f'wrote {eout} ({len(eblob)} bytes)')
    # GPU head file (2026-09-27 OOM fix): the 48 int8 head triples ALONE.
    # The head `Net` uploads its whole `Blob` verbatim; with the chunks inside
    # EMBED that upload was 3.6 GB (TEXT 1.1 + EMBED 3.6 = OOM-reboot on P8).
    # The head file is ~402 MB. `collect_head` re-derives the chunks from the
    # same S10 table (no new numerics — same `fid.quantise` calls, same rows).
    hlayers, htensors = collect_head(rdr)
    hdigest = mc.layer_table_digest(hlayers)
    print(f's10_head: {len(hlayers)} layers, {len(htensors)} tensors, digest {hdigest}')
    hblob, _ = mc.build(hlayers, htensors, mc.GRAPHS['gemma4_head'], sha)
    hout = os.path.join(args.outdir, 'gemma4_head.maml')
    open(hout, 'wb').write(hblob)
    print(f'wrote {hout} ({len(hblob)} bytes)')


# Slim embed layout (see the module docstring): kept source entries, in order,
# with SLIM_PLACEHOLDERS one-element fp16 tensors standing in for 3-6.
SLIM_LAYERS = 35
SLIM_TABLES = 7
SLIM_PLACEHOLDERS = range(3, SLIM_TABLES)
SLIM_TENSORS = SLIM_TABLES + SLIM_LAYERS * 3


def read_maml_table(path):
    """(graph_id, sha256, data_offset, entries) of a .maml; entry = (ndim, dims, dtype, offset, size)."""
    import struct
    with open(path, 'rb') as f:
        header = f.read(mc.HEADER_BYTES)
        if header[:4] != mc.MAGIC:
            raise SystemExit(f'{path}: not a .maml')
        _, graph_id, count = struct.unpack_from('<III', header, 4)
        sha = header[16:48]
        data_offset, _ = struct.unpack_from('<II', header, 48)
        table = f.read(count * mc.TENSOR_ENTRY_BYTES)
    entries = []
    for i in range(count):
        fields = struct.unpack_from('<I4IIII', table, i * mc.TENSOR_ENTRY_BYTES)
        ndim, dims, dtype, offset, size = fields[0], fields[1:5], fields[5], fields[6], fields[7]
        entries.append((ndim, dims[:ndim], dtype, offset, size))
    return graph_id, sha, data_offset, entries


def read_payload(f, data_offset, entry):
    _, _, dtype, offset, size = entry
    f.seek(data_offset + offset)
    want = mc.payload_bytes(dtype, size)
    payload = f.read(want)
    if len(payload) != want:
        raise SystemExit(f'short read: {len(payload)} of {want} bytes')
    return payload


def write_slim_embed(src, out):
    """Slim gemma4_embed.maml from a full one, tables bit-identical (re-read and compared)."""
    import hashlib
    graph_id, sha, data_offset, entries = read_maml_table(src)
    if graph_id != mc.GRAPHS['gemma4_embed']:
        raise SystemExit(f'{src}: graph {graph_id}, not gemma4_embed')
    if len(entries) < SLIM_TENSORS:
        raise SystemExit(f'{src}: {len(entries)} tensors, fewer than the {SLIM_TENSORS} kept')
    tensors = []
    with open(src, 'rb') as f:
        for i in range(SLIM_TENSORS):
            if i in SLIM_PLACEHOLDERS:
                tensors.append(np.zeros(1, dtype=np.float16))
                continue
            entry = entries[i]
            tensors.append(mc.Raw(entry[2], entry[1], entry[4], read_payload(f, data_offset, entry)))
    blob, _ = mc.build([], tensors, graph_id, sha)
    with open(out, 'wb') as f:
        f.write(blob)
    del blob, tensors
    _, _, out_data, out_entries = read_maml_table(out)
    assert len(out_entries) == SLIM_TENSORS, len(out_entries)
    with open(src, 'rb') as a, open(out, 'rb') as b:
        for i in range(SLIM_TENSORS):
            if i in SLIM_PLACEHOLDERS:
                continue
            want, got = entries[i], out_entries[i]
            if want[:3] != got[:3] or want[4] != got[4]:
                raise SystemExit(f'tensor {i}: entry {got} differs from {want}')
            if hashlib.sha256(read_payload(a, data_offset, want)).digest() != \
                    hashlib.sha256(read_payload(b, out_data, got)).digest():
                raise SystemExit(f'tensor {i}: payload differs from the source')
    print(f'wrote {out} ({os.path.getsize(out)} bytes, {SLIM_TENSORS} tensors, '
          f'{SLIM_TENSORS - len(SLIM_PLACEHOLDERS)} bit-identical to {src})')


def collect_embed(rdr):
    """gemma4_embed from S10's own tensors

    Layout matches the artisan path's embed file exactly (same tensor order,
    same layer kinds), so the runtime reads either file identically:
    - working table: exact S2 `embedder` fp32 rows [262144, 1536] via
      GEMMA4_EMBED_DUMP (same requirement as the artisan path; the 2-bit
      in-file packing is a different checkpoint's arrangement in both files).
    - head table: S10 t2698 (`embedder.decode` composite, type 19 2-bit,
      codebook [0,+s,-2s,-s] lo-first — 10/10 vs the golden ranking). The
      tied head reads this raw-scale table (see nets::gemma4::EMBED_GAIN).
    - shared projection: S10's OWN t312 (int8 [8960,1536], 1 scale/row) +
      norm gamma t305 (256). Track-3 decision (live t318 oracle): S10-table +
      x16-on-gather scores 0.99997 vs live t318 while the artisan table peaks
      at 0.42 — the two files are different checkpoints for the dense tables
      (L0 Q/O/gate artisan-vs-S10 full-cos ~0.0006, row-max ~0.10; S10 Q@hidden
      vs live t357 = 0.989, artisan = -0.14), so the S10-control file defaults
      to S10's table. (The old note claiming artisan 1.00000 vs S10 -0.09 was
      scored against a trace.json itself generated with artisan weights — circular.
      Gammas are identical either way, cos 1.000000.)
    - 35 per-layer mmap tables: S10 has no per-layer embedder (that is
      Section 3, not Section 10); these come from the artisan file's
      `per_layer_embeddings.w` INT4 tables, whose signed-nibble read is
      proven (cos 1.0000 vs base S3 output).
    """
    import litertlm_to_maml as lt
    layers, tensors = [], []
    fid = mc.Fidelity()
    dump = os.environ.get('GEMMA4_EMBED_DUMP')
    if not dump or not os.path.exists(dump):
        raise SystemExit(
            'collect_embed needs GEMMA4_EMBED_DUMP=<embed_s2_fp32.bin> '
            '(scripts/ml/gemma4_embed_dump.py)')
    out = np.memmap(dump, dtype=np.float32, mode='r',
                    shape=(262144, 1536)).astype(np.float32)
    m = float(np.abs(out).mean())
    print(f'input_embedding S2 dump {dump}: mean abs {m:.4f}')
    codes, scale = fid.quantise4('input_embedding', out)
    bias = np.zeros(262144, dtype=np.float32)
    tensors.extend([codes, scale, bias])
    layers.append(mc.Layer(len(layers), 'Embedding4', 'input_embedding',
                           'Embedding4 2-bit source', len(tensors) - 3, 3))
    # Per-layer mmap tables: S10 carries none (that is Section 3, not
    # Section 10). Prefer S3's own tables (exact golden source); fall back
    # to the artisan file's signed-nibble read when S3 is absent.
    art_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'analysis',
                            'gpu-sections',
                            'Section2_TFLiteModel_tf_lite_artisan_text_decoder.tflite')
    s3rdr = S3Reader(S3) if os.path.exists(S3) else None
    art = lt.LitertlmReader(art_path) if s3rdr is None else None
    if s3rdr is None and art is None:
        raise SystemExit('collect_embed needs S3 (analysis/base-sections/...) or the artisan file')
    emit_combine(fid, layers, tensors, rdr)
    for index in range(35):
        if s3rdr is not None:
            out = s3rdr.table(index)
        else:
            at = f'transformer.layer_{index}.per_layer_embeddings.w'
            w = np.frombuffer(art.raw(at), dtype=np.uint8)
            assert w.size >= 262144 * 128, f'{at}: {w.size}'
            w = w[:262144 * 128]
            lo = (w & 0xF).astype(np.float32)
            hi = ((w >> 4) & 0xF).astype(np.float32)
            flat = np.empty(262144 * 256, dtype=np.float32)
            flat[0::2] = lo
            flat[1::2] = hi
            flat = np.where(flat >= 8, flat - 16, flat).reshape(262144, 256)
            s = np.frombuffer(art.raw(at + '_quantized_scale'), dtype=np.float32)
            assert s.size == 262144, f'{at} scales: {s.size}'
            out = flat * s[:, None]
        codes, scale = fid.quantise4(f'layer_{index}.per_layer_embeddings', out)
        bias = np.zeros(262144, dtype=np.float32)
        tensors.extend([codes, scale, bias])
        layers.append(mc.Layer(len(layers), 'Linear4', f'layer_{index}.per_layer_embeddings',
                               'Linear4 mmap table', len(tensors) - 3, 3))
    # Head table LAST (see TABLES): appended after the per-layer tables so
    # their file entries (7+i*3) match the artisan-path file exactly and old
    # files without a head table still parse for gather.
    #
    # GPU tied-head (2026-09-26): the head is ALSO emitted as HEAD_SPLITS
    # rank-4 fp16 chunks ([N,1536,1,1] + zero [N] bias each) that
    # Builder::conv binds directly (kernel+bias, no bias-free path exists).
    # One 262144-wide fp16 tensor spans ~768 MiB, far over the ~96 MiB
    # segment-window reach at guaranteed 128 MiB maxStorageBufferRange
    # (vulkan/segment.rs `for_op` refuses it: "Split the tensor in the
    # converter"). 16x16384 chunks are ~50 MB each — inside the window with
    # margin on both host and P8. Chunk tensors follow the legacy rank-2
    # table so old files (112 tensors, no head) still parse for gather and
    # indices 0..111 are unchanged; the runtime binds chunks when present
    # and falls back to the host head otherwise. Vocab order is preserved
    # (chunk s holds classes [s*N,(s+1)*N)), so concat(chunks) == table.
    head = rdr.dequant2(2698, 262144, 1536)
    head = np.ascontiguousarray(head, dtype=np.float32).astype(np.float16)
    tensors.append(head)
    layers.append(mc.Layer(len(layers), 'Embedding4', 'head_table',
                           'Embedding4 raw-scale S10 decode table fp16',
                           len(tensors) - 1, 1))
    n = head.shape[0]
    assert n == 262144, n
    per = n // HEAD_SPLITS
    assert per * HEAD_SPLITS == n, (n, HEAD_SPLITS)
    for s in range(HEAD_SPLITS):
        rows = head[s * per:(s + 1) * per].astype(np.float32)
        assert rows.shape == (per, 1536), rows.shape
        kernel = np.ascontiguousarray(rows.reshape(per, 1536, 1, 1))
        bias = np.zeros(per, dtype=np.float32)
        tensors.extend([kernel, bias])
        layers.append(mc.Layer(len(layers), 'Linear', f'head_split_{s}',
                               f'Linear w=[{per}, 1536, 1, 1] dtype=fp16 b=[{per}]',
                               len(tensors) - 2, 2))
    print(f'head_table: rank-2 fp16 [{n}, 1536] + {HEAD_SPLITS} rank-4 '
          f'fp16 chunks of [{per}, 1536, 1, 1] (+ zero bias)')
    # int8 GPU-head chunks (Phase 2, 2026-09-26): the same rows quantised
    # per-channel (`fid.quantise`, symmetric int8 * fp16 scale), 3 tensors
    # per chunk (kernel, scale, bias). ~25 MB/chunk vs ~50 MB fp16 (~2x
    # bandwidth), and chunks auto-route to `ConvVecInt8` at 1 position
    # (mod_part9.rs), fixing the fp16 `ConvPoint` 93.75% idle-lane waste with
    # no code change. The fp16 chunks above stay in-file during transition:
    # the runtime binds int8 when present, fp16 otherwise.
    # INVARIANT (byte-identity with the head file, 2026-09-27): these rows
    # are the fp16-rounded `head` table, NOT fp32-direct dequant — keep it
    # that way. `collect_head` rounds identically so its 48 triples are
    # byte-identical to these chunks (quantising fp32-direct flips ~6-11%
    # of codes by ±1, a second numerics variant for 0.00002 fidelity).
    # CAUTION (binding): int8-per-channel over an S10-2-bit source is a
    # requant — must pass the argmax-9079 gate before shipping, never cosine
    # alone. Do NOT use the WORKING int4 table as head even with /39.25
    # (wrong source + LOGIT_CAP saturation).
    for s in range(HEAD_SPLITS):
        rows = head[s * per:(s + 1) * per].astype(np.float32)
        kernel8, scale8 = fid.quantise(f'head_split8_{s}',
                                       np.ascontiguousarray(rows).reshape(per, 1536, 1, 1))
        bias8 = np.zeros(per, dtype=np.float32)
        tensors.extend([kernel8, scale8, bias8])
        layers.append(mc.Layer(len(layers), 'Linear8', f'head_split8_{s}',
                               f'Linear8 w=[{per}, 1536, 1, 1] dtype=int8 b=[{per}]',
                               len(tensors) - 3, 3))
    print(f'head_table int8: {HEAD_SPLITS} chunks of [{per}, 1536, 1, 1] '
          f'int8 (+ [per] scale, zero bias)')
    fid.report(mc.MIN_INT4_COSINE)
    return layers, tensors


def collect_head(rdr):
    """gemma4_head: the 48 int8 head triples ALONE (OOM fix, 2026-09-27).

    Same S10 t2698 source, same fp16-rounded rows, same `fid.quantise`
    calls, same row order as the int8 chunks `collect_embed` appends —
    factored so the head `Net` uploads ~402 MB instead of the whole
    3.6 GB EMBED file. The fp16 rounding mirrors `collect_embed`'s head
    section exactly (quantising the same rows the fp16 chunks store), so
    the head file's 48 triples are byte-identical to the EMBED int8
    chunks — one numerics variant in the field, byte-comparable.
    Chunk `s` owns tensors `s * 3 .. s * 3 + 2` (kernel
    `[per, 1536, 1, 1]` int8, scale `[per]` fp16, bias `[per]` fp16);
    vocab order preserved.
    """
    layers, tensors = [], []
    fid = mc.Fidelity()
    head = rdr.dequant2(2698, 262144, 1536)
    # fp16 round FIRST, exactly as `collect_embed` does before its int8
    # chunks: quantising fp32-direct flips ~6-11% of codes by ±1 (measured
    # 2026-09-27, scales ~0.05%), a second numerics variant for no gain
    # (direct-fp32 fidelity 0.999922 vs fp16-rounded 0.999900, both far
    # above MIN_INT8_COSINE 0.999).
    head = np.ascontiguousarray(head, dtype=np.float32).astype(np.float16)
    n = head.shape[0]
    assert n == 262144, n
    per = n // HEAD_SPLITS
    assert per * HEAD_SPLITS == n, (n, HEAD_SPLITS)
    for s in range(HEAD_SPLITS):
        rows = head[s * per:(s + 1) * per].astype(np.float32)
        kernel8, scale8 = fid.quantise(f'head_split8_{s}',
                                       np.ascontiguousarray(rows).reshape(per, 1536, 1, 1))
        bias8 = np.zeros(per, dtype=np.float32)
        tensors.extend([kernel8, scale8, bias8])
        layers.append(mc.Layer(len(layers), 'Linear8', f'head_split8_{s}',
                               f'Linear8 w=[{per}, 1536, 1, 1] dtype=int8 b=[{per}]',
                               len(tensors) - 3, 3))
    print(f'head file: {HEAD_SPLITS} int8 chunks of [{per}, 1536, 1, 1] '
          f'(+ [per] scale, zero bias)')
    fid.report(mc.MIN_INT4_COSINE)
    return layers, tensors


if __name__ == '__main__':
    main()
