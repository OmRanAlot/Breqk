# Breqk 🍃

A modern React Native mobile application designed to help users reduce screen time and "doom scrolling" by implementing app blocking with customizable delay screens. The app features a modern, minimal UI design that promotes digital wellness and mindful technology use.

> **⚠️ IMPORTANT**: Currently only works on Android with known bugs. iOS support is not yet implemented.

## 🎯 Use Cases

This app is designed for users who want to:

- Reduce screen time and digital distractions
- Break habitual app usage patterns
- Practice mindful technology use with intentional delays
- Monitor their app usage patterns and progress
- Build healthy digital habits through gamification

## 🎨 Design Features

### Modern UI Design

- Minimal, clean, and distraction-free interface
- Consistent color scheme with proper contrast and accessibility
- Smooth animations and transitions (150ms)
- Card-based layout with subtle shadows and rounded corners
- Responsive design that works across different screen sizes

## 🏗️ App Structure

### Three Main Tabs

#### 1. Home Tab
- **Daily Overview Card**: Shows today's saved screen time and blocked apps count
- **Blocked Apps List**: Horizontal chips displaying currently blocked applications
- **Delay Settings Preview**: Current delay time and focus mode information
- **Quick Actions**: Start/pause session buttons

#### 2. Customize Tab
- **App Selector**: Toggle switches for selecting which apps to block
- **Delay Time Slider**: Adjustable delay from 5 seconds to 120 seconds
- **Focus Modes**: Pre-configured blocking profiles (Study, Work, Sleep, Custom)
- **Delay Screen Message Editor**: Customizable message for delay screens

#### 3. Progress Tab
- **Progress Bar**: Visual representation of daily goal progress
- **Streak Counter**: Current and best streak tracking
- **Achievements Grid**: 2-column grid of unlockable badges
- **Weekly Graph**: Bar chart showing screen time saved per day

## 🚀 Getting Started

### Prerequisites

- Node.js >= 18
- React Native development environment
- Android Studio (for Android development)
- Xcode (for iOS development, though iOS is not currently supported)

### Installation

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd Breqk
   ```

2. **Install dependencies**
   ```bash
   npm install
   ```

3. **Install additional dependencies**
   ```bash
   npm install @react-native-community/slider
   ```

4. **iOS specific setup** (if developing for iOS in the future)
   ```bash
   cd ios
   bundle install
   bundle exec pod install
   cd ..
   ```

### Running the App

- **Android**: `npm run android`
- **iOS**: `npm run ios` (not currently functional)
- **Start Metro**: `npm start`

## 🚧 Development Status

### Current Status

- ✅ **Android**: Fully implemented with native modules (with bugs)
- ✅ **React Native UI**: Complete modern design implementation
- ⚠️ **iOS**: Basic setup (no native implementation yet)
- ⚠️ **Testing**: Basic test coverage

### Known Issues

- **Android only**: App currently only functions on Android devices
- **Multiple bugs**: Known stability and functionality issues on Android
- Slider component requires `@react-native-community/slider` dependency
- Vector icons require proper linking in native projects
- Some TypeScript warnings may appear (non-blocking)

### Future Enhancements

- Bug fixes for Android implementation
- iOS native implementation
- Enhanced testing coverage
- Dark mode support
- Advanced analytics and insights
- Social features and challenges

## 🔒 Security & Privacy

- No data collection or external transmission
- Local storage only for user preferences
- Minimal permissions required for core functionality
- VPN service used minimally for background operation
- User control over all blocking settings

## 📄 License

This project is private and proprietary.

---

**Note**: This is an early-stage project under active development. Expect bugs and incomplete features, especially on the Android platform. Contributions and feedback are welcome as we work to improve stability and add iOS support.
