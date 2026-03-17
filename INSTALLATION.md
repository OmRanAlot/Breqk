# Installation Guide for Bare React Native Project

This guide is specifically for setting up the Breqk app in a **bare React Native project** (not Expo).

## Prerequisites

- **Node.js** >= 18
- **React Native CLI** installed globally
- **Android Studio** (for Android development)
- **Xcode** (for iOS development - macOS only)
- **Java Development Kit (JDK)** 11 or newer

## Setup Steps

### 1. Install Dependencies

```bash
# Install all npm dependencies
npm install

# Install the slider component specifically
npm install @react-native-community/slider
```

### 2. Android Setup

#### Install Android Dependencies
```bash
# Navigate to Android directory
cd android

# Clean and build (if needed)
./gradlew clean
./gradlew build

# Return to root
cd ..
```

#### Run on Android
```bash
# Start Metro bundler
npm start

# In another terminal, run Android
npm run android
```

### 3. iOS Setup (macOS only)

#### Install CocoaPods Dependencies
```bash
# Navigate to iOS directory
cd ios

# Install CocoaPods if not already installed
sudo gem install cocoapods

# Install pod dependencies
pod install

# Return to root
cd ..
```

#### Run on iOS
```bash
# Start Metro bundler
npm start

# In another terminal, run iOS
npm run ios
```

## Project Structure

```
Breqk/
├── App.tsx                 # Main app with navigation
├── components/
│   ├── Home/
│   │   └── home.js        # Home tab with overview
│   ├── Customize/
│   │   └── customize.js   # Settings customization
│   └── Progress/
│       └── progress.js    # Progress tracking
├── android/                # Native Android code
├── ios/                    # Native iOS code
└── package.json
```

## Key Features

### Modern UI Design
- **Three-tab navigation**: Home, Customize, Progress
- **Emoji-based icons**: No external icon dependencies
- **Consistent styling**: Modern color scheme and typography
- **Responsive layout**: Works on different screen sizes

### Components
- **Home**: Daily overview, blocked apps, quick actions
- **Customize**: App selection, delay settings, focus modes
- **Progress**: Statistics, achievements, weekly graphs

## Troubleshooting

### Common Issues

#### Metro Bundler Issues
```bash
# Clear Metro cache
npx react-native start --reset-cache
```

#### Android Build Issues
```bash
cd android
./gradlew clean
./gradlew build
cd ..
```

#### iOS Build Issues
```bash
cd ios
pod deintegrate
pod install
cd ..
```

#### Dependencies Issues
```bash
# Remove node_modules and reinstall
rm -rf node_modules
npm install
```

### Platform-Specific Notes

#### Android
- Ensure Android SDK is properly configured
- Check that ANDROID_HOME environment variable is set
- Verify that Android emulator or device is running

#### iOS
- Requires macOS and Xcode
- Ensure CocoaPods is installed and up to date
- Check that iOS Simulator or device is available

## Development

### Start Development Server
```bash
npm start
```

### Run on Device/Emulator
```bash
# Android
npm run android

# iOS
npm run ios
```

### Build for Production
```bash
# Android
cd android
./gradlew assembleRelease
cd ..

# iOS
# Use Xcode to archive and distribute
```

## Dependencies

### Core Dependencies
- `react-native`: 0.80.2
- `react`: 19.1.0
- `@react-navigation/*`: Navigation components
- `@react-native-community/slider`: Customizable slider component

### Development Dependencies
- `typescript`: 5.0.4
- `@types/react-native-vector-icons`: Type definitions
- `eslint`, `prettier`: Code quality tools

## Notes

- This is a **bare React Native project**, not Expo
- All icons are emoji-based for simplicity and compatibility
- The slider component requires proper linking in native projects
- TypeScript is configured but some type warnings may appear (non-blocking)

## Support

For issues specific to this project, check the main README.md file or contact the development team. 