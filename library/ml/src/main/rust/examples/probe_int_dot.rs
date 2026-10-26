//! Reports what the GPU offers for int8 matrix work: `VK_KHR_shader_integer_dot_product`'s
//! acceleration flags and every `VK_KHR_cooperative_matrix` shape.
//!
//! ```text
//! cargo run --offline -p modelrunner --example probe_int_dot
//! ```
use std::ffi::CStr;

use ash::vk;
use modelrunner::vulkan::context;

fn main() {
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => {
            println!("no usable Vulkan device: {why}");
            return;
        }
    };
    let (instance, physical) = (&context.instance, context.physical_device);
    // SAFETY: plain queries on the physical device the context holds alive.
    let names: Vec<String> = unsafe { instance.enumerate_device_extension_properties(physical) }
        .unwrap_or_default()
        .iter()
        .map(|e| {
            // SAFETY: the driver NUL-terminates extension names inside the fixed array.
            unsafe { CStr::from_ptr(e.extension_name.as_ptr()) }.to_string_lossy().into_owned()
        })
        .collect();
    for wanted in [
        "VK_KHR_shader_integer_dot_product",
        "VK_KHR_cooperative_matrix",
        "VK_KHR_8bit_storage",
        "VK_KHR_shader_float16_int8",
    ] {
        println!("{wanted:40} {}", names.iter().any(|n| n == wanted));
    }
    println!("runtime enabled int8 dot: {}", context.int_dot);

    let mut dot = vk::PhysicalDeviceShaderIntegerDotProductProperties::default();
    let mut properties = vk::PhysicalDeviceProperties2::default().push_next(&mut dot);
    // SAFETY: as above; an unknown chained struct is left zeroed by a driver without it.
    unsafe { instance.get_physical_device_properties2(physical, &mut properties) };
    println!("4x8 packed accelerated: signed {} unsigned {} mixed {}",
        dot.integer_dot_product4x8_bit_packed_signed_accelerated,
        dot.integer_dot_product4x8_bit_packed_unsigned_accelerated,
        dot.integer_dot_product4x8_bit_packed_mixed_signedness_accelerated);
    println!("acc-sat 4x8 packed accelerated: signed {} mixed {}",
        dot.integer_dot_product_accumulating_saturating4x8_bit_packed_signed_accelerated,
        dot.integer_dot_product_accumulating_saturating4x8_bit_packed_mixed_signedness_accelerated);

    if !names.iter().any(|n| n == "VK_KHR_cooperative_matrix") {
        return;
    }
    let coop = ash::khr::cooperative_matrix::Instance::new(&context.entry, instance);
    // SAFETY: the extension is present, so the instance-level entry point resolves.
    match unsafe { coop.get_physical_device_cooperative_matrix_properties(physical) } {
        Ok(shapes) => {
            println!("cooperative matrix shapes ({}):", shapes.len());
            for s in shapes {
                println!("  M {} N {} K {}  A {} B {} C {} R {}  sat {} scope {}",
                    s.m_size, s.n_size, s.k_size, s.a_type.as_raw(), s.b_type.as_raw(),
                    s.c_type.as_raw(), s.result_type.as_raw(), s.saturating_accumulation,
                    s.scope.as_raw());
            }
            println!("  (component types: 0 f16, 1 f32, 3 s8, 5 s32, 7 u8, 9 u32; scope 3 subgroup)");
        }
        Err(e) => println!("cooperative matrix query failed: {}", e.as_raw()),
    }
}
