use ash::vk;
use ash::Entry;
use std::ffi::{CString, c_char};
use std::os::raw::c_void;

#[repr(C)]
pub struct ANativeWindow {
    _private: [u8; 0],
}

#[cfg(target_os = "android")]
#[link(name = "android")]
extern "C" {
    pub fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut ANativeWindow;
    pub fn ANativeWindow_acquire(window: *mut ANativeWindow);
    pub fn ANativeWindow_release(window: *mut ANativeWindow);
}

#[cfg(not(target_os = "android"))]
#[allow(non_snake_case)]
pub unsafe fn ANativeWindow_fromSurface(_env: *mut c_void, _surface: *mut c_void) -> *mut ANativeWindow {
    std::ptr::null_mut()
}
#[cfg(not(target_os = "android"))]
#[allow(non_snake_case)]
pub unsafe fn ANativeWindow_acquire(_window: *mut ANativeWindow) {}
#[cfg(not(target_os = "android"))]
#[allow(non_snake_case)]
pub unsafe fn ANativeWindow_release(_window: *mut ANativeWindow) {}

pub struct VulkanContext {
    pub entry: Entry,
    pub instance: ash::Instance,
    pub surface_loader: ash::khr::surface::Instance,
    pub android_surface_loader: ash::khr::android_surface::Instance,
    pub surface: vk::SurfaceKHR,
    pub physical_device: vk::PhysicalDevice,
    pub device: ash::Device,
    pub queue_family_index: u32,
    pub queue: vk::Queue,
    pub device_properties: vk::PhysicalDeviceProperties,
}

impl VulkanContext {
    pub unsafe fn new(window: *mut ANativeWindow, _width: i32, _height: i32) -> Result<Self, String> {
        let entry = unsafe { Entry::load().map_err(|e| format!("Entry::load failed: {e:?}"))? };
        let app_name = CString::new("Voxels").unwrap();
        let exts = Self::required_instance_extensions();
        let ext_ptrs: Vec<*const c_char> = exts.iter().map(|s| s.as_ptr()).collect();
        let app_info = vk::ApplicationInfo::default()
            .application_name(&app_name)
            .application_version(vk::make_api_version(0, 1, 0, 0))
            .engine_name(&app_name)
            .engine_version(vk::make_api_version(0, 1, 0, 0))
            .api_version(vk::make_api_version(0, 1, 1, 0));
        let create_info = vk::InstanceCreateInfo::default()
            .application_info(&app_info)
            .enabled_extension_names(&ext_ptrs);
        let instance = entry.create_instance(&create_info, None).map_err(|e| format!("create_instance {e:?}"))?;
        let surface_loader = ash::khr::surface::Instance::new(&entry, &instance);
        let android_surface_loader = ash::khr::android_surface::Instance::new(&entry, &instance);
        let android_ci = vk::AndroidSurfaceCreateInfoKHR::default().window(window as *mut _);
        let surface = android_surface_loader.create_android_surface(&android_ci, None).map_err(|e| format!("create_android_surface {e:?}"))?;
        let pdevices = instance.enumerate_physical_devices().map_err(|e| format!("enum pdevices {e:?}"))?;
        if pdevices.is_empty() { return Err("no physical devices".into()); }
        let mut chosen = None;
        let mut qfi = 0;
        for &pd in &pdevices {
            let qprops = instance.get_physical_device_queue_family_properties(pd);
            for (i, qp) in qprops.iter().enumerate() {
                if !qp.queue_flags.contains(vk::QueueFlags::GRAPHICS) { continue; }
                let present = surface_loader.get_physical_device_surface_support(pd, i as u32, surface).unwrap_or(false);
                if present { chosen = Some(pd); qfi = i as u32; break; }
            }
            if chosen.is_some() { break; }
        }
        let physical_device = chosen.ok_or("no suitable device")?;
        let props = instance.get_physical_device_properties(physical_device);
        let priorities = [1.0f32];
        let q_info = vk::DeviceQueueCreateInfo::default().queue_family_index(qfi).queue_priorities(&priorities);
        let dev_exts = [ash::khr::swapchain::NAME.as_ptr()];
        let dev_ci = vk::DeviceCreateInfo::default().queue_create_infos(std::slice::from_ref(&q_info)).enabled_extension_names(&dev_exts);
        let device = instance.create_device(physical_device, &dev_ci, None).map_err(|e| format!("create_device {e:?}"))?;
        let queue = device.get_device_queue(qfi, 0);
        Ok(Self { entry, instance, surface_loader, android_surface_loader, surface, physical_device, device, queue_family_index: qfi, queue, device_properties: props })
    }
    /// Binds the context to a new native window after the old surface was lost (app backgrounded,
    /// screen off). The instance, device and everything allocated on it survive; only the
    /// `VkSurfaceKHR` is recreated. Fails if the device can't present to the new surface, in which
    /// case the caller rebuilds the whole renderer.
    ///
    /// # Safety
    /// `window` must be a live, acquired `ANativeWindow`, and no swapchain may still reference the
    /// previous surface.
    pub unsafe fn replace_surface(&mut self, window: *mut ANativeWindow) -> Result<(), String> {
        unsafe { self.drop_surface(); }
        let ci = vk::AndroidSurfaceCreateInfoKHR::default().window(window as *mut _);
        let surface = unsafe { self.android_surface_loader.create_android_surface(&ci, None) }
            .map_err(|e| format!("create_android_surface {e:?}"))?;
        let ok = unsafe {
            self.surface_loader.get_physical_device_surface_support(self.physical_device, self.queue_family_index, surface)
        }.unwrap_or(false);
        if !ok {
            unsafe { self.surface_loader.destroy_surface(surface, None); }
            return Err("device cannot present to the new surface".into());
        }
        self.surface = surface;
        Ok(())
    }
    /// Destroys the window surface (the window is going away) but keeps the device.
    ///
    /// # Safety
    /// The swapchain built on this surface must already be destroyed.
    pub unsafe fn drop_surface(&mut self) {
        if self.surface != vk::SurfaceKHR::null() {
            unsafe { self.surface_loader.destroy_surface(self.surface, None); }
            self.surface = vk::SurfaceKHR::null();
        }
    }
    fn required_instance_extensions() -> Vec<CString> {
        vec![ash::khr::surface::NAME.to_owned(), ash::khr::android_surface::NAME.to_owned()]
    }
    pub fn destroy(&mut self) {
        unsafe {
            self.device.device_wait_idle().ok();
            self.device.destroy_device(None);
            self.drop_surface();
            self.instance.destroy_instance(None);
        }
    }
}
