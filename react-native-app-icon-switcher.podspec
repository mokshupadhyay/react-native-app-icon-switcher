require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-app-icon-switcher"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]
  s.platforms    = { :ios => (defined?(min_ios_version_supported) ? min_ios_version_supported : "13.0") }
  s.source       = { :git => package["repository"]["url"].sub(/^git\+/, ""), :tag => "v#{s.version}" }
  s.source_files = "ios/**/*.{h,m,mm}"
  # Only the Objective-C manager is public, so Swift can `import react_native_app_icon_switcher`
  # without the module header's C++ codegen imports.
  s.public_header_files = "ios/FlexAppIconManager.h"
  s.pod_target_xcconfig = { "DEFINES_MODULE" => "YES" }

  if respond_to?(:install_modules_dependencies, true)
    # React Native 0.71+: adds React-Core, codegen and New Architecture flags.
    install_modules_dependencies(s)
  else
    s.dependency "React-Core"
  end
end
