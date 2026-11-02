//! Reports the three device limits a `.maml` file has to fit inside, and finds the largest
//! single device-local allocation that actually succeeds.
//!
//! ```text
//! cargo run --offline -p modelrunner --example probe_limits
//! ```
//!
//! # Why this exists
//!
//! `modelrunner::vulkan::run` splits the weights into blocks of at most `maxMemoryAllocationSize`
//! (see `modelrunner::vulkan::segment`), so the reported limit decides how many allocations a
//! model takes. What it cannot decide is whether each one actually succeeds.
//!
//! A reported limit and a limit that works are different things — a driver may advertise a
//! number it cannot honour once the heap is shared with a compositor — so this asks the device
//! for the number *and* then tries the allocation.
//!
//! # Reading the result on a desktop GPU
//!
//! A discrete card with its own VRAM will report and satisfy allocations far beyond anything a
//! phone will. This probe can therefore **rule a model out** but never rule one in: a failure
//! here is conclusive, a success here says nothing about an Adreno or Mali part sharing system
//! RAM with the rest of Android. Run it on the target handset before believing it.
use std::sync::Arc;

use ash::vk;
use modelrunner::vulkan::buffers::Buffer;
use modelrunner::vulkan::context::{self, Context};

/// Sizes worth naming in the output, largest last.
///
/// The first is the spec's guaranteed floor for `maxMemoryAllocationSize`; the rest are the
/// weights sizes of models the runtime either loads today or has been proposed for.
const LANDMARKS: &[(&str, u64)] = &[
    ("NLLB-200 600M distilled, int8 (ships today)", 617 << 20),
    ("Vulkan guaranteed maxMemoryAllocationSize floor", 1 << 30),
    ("Gemma 3n E2B, per-block int4 BLOCK=64 (~4.25 bpw)", 2_660_000_000),
    ("Gemma 3n E2B, per-block int4 BLOCK=32 (~4.5 bpw)", 2_800_000_000),
];

fn main() {
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => {
            println!("no usable Vulkan device: {why}");
            println!();
            println!("This runtime requires VK_KHR_shader_float16_int8. A device without it");
            println!("reports itself unavailable rather than falling back to the CPU.");
            return;
        }
    };

    describe_device(&context);
    describe_limits(&context);
    describe_heaps(&context);
    probe_allocations(&context);
}

/// Name and driver of the device the runtime actually chose.
fn describe_device(context: &Arc<Context>) {
    // SAFETY: a plain property query on the physical device the context already selected and
    // holds alive. No pointers are retained past the call.
    let properties =
        unsafe { context.instance.get_physical_device_properties(context.physical_device) };
    let name = properties
        .device_name_as_c_str()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_else(|_| "<unnamed>".to_owned());
    let api = properties.api_version;
    println!("device   {name}");
    println!(
        "type     {}, Vulkan {}.{}.{}",
        device_type(properties.device_type),
        vk::api_version_major(api),
        vk::api_version_minor(api),
        vk::api_version_patch(api),
    );
    println!();
}

/// `VkPhysicalDeviceType` as a word.
///
/// Spelled out because `ash` is built with `default-features = false`, which drops the `debug`
/// feature and with it every `Debug` impl on its enums.
fn device_type(kind: vk::PhysicalDeviceType) -> &'static str {
    match kind {
        vk::PhysicalDeviceType::INTEGRATED_GPU => "integrated GPU",
        vk::PhysicalDeviceType::DISCRETE_GPU => "discrete GPU",
        vk::PhysicalDeviceType::VIRTUAL_GPU => "virtual GPU",
        vk::PhysicalDeviceType::CPU => "CPU",
        _ => "other",
    }
}

/// The three limits, each against the floor the spec guarantees.
fn describe_limits(context: &Arc<Context>) {
    let limits = context.limits;
    println!("limits");
    println!(
        "  maxMemoryAllocationSize          {:>14}   (spec floor {})",
        bytes(limits.max_memory_allocation_size),
        bytes(1 << 30),
    );
    println!(
        "  maxStorageBufferRange            {:>14}   (spec floor {})",
        bytes(limits.max_storage_buffer_range),
        bytes(128 << 20),
    );
    println!(
        "  minStorageBufferOffsetAlignment  {:>14}",
        limits.min_storage_buffer_offset_alignment,
    );
    if std::env::var("MODELRUNNER_MAX_STORAGE_RANGE").is_ok() {
        println!("  (MODELRUNNER_MAX_STORAGE_RANGE is set, so the range above is forced)");
    }
    if std::env::var("MODELRUNNER_MAX_ALLOCATION").is_ok() {
        println!("  (MODELRUNNER_MAX_ALLOCATION is set, so the allocation limit above is forced)");
    }
    println!();
}

/// Every memory heap, flagging the device-local ones an allocation can come from.
fn describe_heaps(context: &Arc<Context>) {
    // SAFETY: as `describe_device`. The returned struct is plain data, copied out by value.
    let memory =
        unsafe { context.instance.get_physical_device_memory_properties(context.physical_device) };
    println!("heaps");
    for index in 0..memory.memory_heap_count as usize {
        let heap = memory.memory_heaps[index];
        let local = heap.flags.contains(vk::MemoryHeapFlags::DEVICE_LOCAL);
        println!(
            "  {index}  {:>12}   {}",
            bytes(heap.size),
            if local { "DEVICE_LOCAL" } else { "host" },
        );
    }
    println!();
}

/// Try the named sizes, then bisect for the true ceiling.
fn probe_allocations(context: &Arc<Context>) {
    println!("allocations  (one Buffer::device_local, as run.rs makes for the weights)");
    let mut largest_ok = 0u64;
    for &(what, size) in LANDMARKS {
        let ok = try_allocate(context, size);
        if ok {
            largest_ok = largest_ok.max(size);
        }
        println!("  {:>12}  {}   {what}", bytes(size), if ok { "ok  " } else { "FAIL" });
    }
    println!();

    let reported = context.limits.max_memory_allocation_size;
    let ceiling = if try_allocate(context, reported) {
        println!(
            "largest successful single allocation  {} (the reported limit, honoured)",
            bytes(reported)
        );
        reported
    } else {
        // Bisect between the largest size known to work and the smallest known to fail.
        // Starting the upper bound at the reported limit keeps this to ~30 probes.
        let mut low = largest_ok;
        let mut high = reported;
        while low + (1 << 20) < high {
            let mid = low + (high - low) / 2;
            if try_allocate(context, mid) {
                low = mid;
            } else {
                high = mid;
            }
        }
        println!("largest successful single allocation  {}", bytes(low));
        println!(
            "  the reported {} is not backed by memory; this is the heap talking",
            bytes(reported)
        );
        low
    };
    println!();

    let needed = 2_800_000_000u64;
    if ceiling >= needed {
        println!("verdict  fits Gemma 3n E2B at int4 ({}) in one buffer", bytes(needed));
    } else {
        println!("verdict  does NOT fit Gemma 3n E2B at int4 ({}) in one buffer", bytes(needed));
        println!("         the runtime will split its weights across several blocks");
    }
    println!();
    println!("A desktop GPU passing this says nothing about a phone. See the module docs.");
}

/// Whether one device-local allocation of `size` succeeds. The buffer is freed immediately.
fn try_allocate(context: &Arc<Context>, size: u64) -> bool {
    if size == 0 || size > context.limits.max_memory_allocation_size {
        return false;
    }
    Buffer::device_local(context, size as vk::DeviceSize).is_ok()
}

/// A byte count as the unit a human would read it in.
///
/// `u64::MAX` is called out rather than rendered: a driver reporting it means "no limit beyond
/// the heap", and printing 16 billion GiB reads as a bug in the probe.
fn bytes(value: u64) -> String {
    if value == u64::MAX {
        return "unbounded".to_owned();
    }
    const UNITS: [(&str, u64); 4] =
        [("TiB", 1 << 40), ("GiB", 1 << 30), ("MiB", 1 << 20), ("KiB", 1 << 10)];
    for (unit, scale) in UNITS {
        if value >= scale {
            return format!("{:.2} {unit}", value as f64 / scale as f64);
        }
    }
    format!("{value} B")
}
