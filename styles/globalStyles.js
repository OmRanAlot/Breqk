// Global styles for Breqk
// Uses design tokens from design/tokens.ts
// All components should import and use these styles for consistency

import { StyleSheet, Platform } from 'react-native';
const tokens = require('../design/tokens').default;

const { dark, spacing, radii, typography, colors, shadows } = tokens;

// ==========================================
// GLOBAL STYLES
// ==========================================

export const globalStyles = StyleSheet.create({
    // ==========================================
    // CONTAINERS
    // ==========================================
    container: {
        flex: 1,
        backgroundColor: dark.background,
    },
    
    scrollContainer: {
        flex: 1,
        backgroundColor: dark.background,
    },
    
    contentWrapper: {
        paddingHorizontal: spacing.md,
        paddingTop: spacing.lg,
        paddingBottom: spacing.xl,
    },

    // ==========================================
    // HEADERS
    // ==========================================
    header: {
        marginBottom: spacing.lg,
    },
    
    headerWithLogo: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: spacing.lg,
    },
    
    logo: {
        fontSize: 42,
        marginRight: spacing.md,
        lineHeight: 42,
    },

    // ==========================================
    // TYPOGRAPHY
    // ==========================================
    title: {
        fontSize: typography.h1.size,
        lineHeight: typography.h1.lineHeight,
        fontWeight: '700',
        color: dark.textPrimary,
        ...(Platform.OS === 'ios' 
            ? { fontFamily: typography.fontFamilyIOSDisplay } 
            : { fontFamily: typography.fontFamilyAndroid }),
    },
    
    subtitle: {
        fontSize: typography.bodyLarge.size,
        lineHeight: typography.bodyLarge.lineHeight,
        color: dark.textSecondary,
        marginTop: spacing.xs,
    },
    
    sectionTitle: {
        fontSize: typography.h2.size,
        lineHeight: typography.h2.lineHeight,
        fontWeight: '700',
        color: dark.textPrimary,
        marginBottom: spacing.md,
    },
    
    cardTitle: {
        fontSize: typography.h2.size,
        fontWeight: '700',
        color: dark.textPrimary,
        marginBottom: spacing.lg,
    },
    
    bodyText: {
        fontSize: typography.body.size,
        lineHeight: typography.body.lineHeight,
        color: dark.textPrimary,
    },
    
    bodyTextSecondary: {
        fontSize: typography.body.size,
        lineHeight: typography.body.lineHeight,
        color: dark.textSecondary,
    },
    
    captionText: {
        fontSize: typography.caption.size,
        lineHeight: typography.caption.lineHeight,
        color: dark.textSecondary,
    },

    // ==========================================
    // CARDS
    // ==========================================
    card: {
        backgroundColor: dark.surface,
        borderRadius: radii.xxl,
        padding: spacing.lg,
        marginBottom: spacing.lg,
        ...shadows.md,
    },
    
    cardLarge: {
        backgroundColor: dark.surface,
        borderRadius: radii.xxl,
        padding: spacing.xl,
        marginBottom: spacing.lg,
        ...shadows.lg,
    },
    
    cardSmall: {
        backgroundColor: dark.surface,
        borderRadius: radii.lg,
        padding: spacing.md,
        marginBottom: spacing.md,
        ...shadows.sm,
    },

    // ==========================================
    // BUTTONS
    // ==========================================
    buttonPrimary: {
        backgroundColor: colors.primary500,
        paddingVertical: spacing.md,
        paddingHorizontal: spacing.lg,
        borderRadius: radii.xl,
        alignItems: 'center',
        justifyContent: 'center',
        ...shadows.md,
    },
    
    buttonPrimaryText: {
        color: '#FFFFFF',
        fontSize: typography.body.size,
        fontWeight: '600',
    },
    
    buttonSecondary: {
        backgroundColor: dark.surface,
        paddingVertical: spacing.md,
        paddingHorizontal: spacing.lg,
        borderRadius: radii.xl,
        alignItems: 'center',
        justifyContent: 'center',
        borderWidth: 1,
        borderColor: dark.divider,
    },
    
    buttonSecondaryText: {
        color: dark.textPrimary,
        fontSize: typography.body.size,
        fontWeight: '600',
    },

    // ==========================================
    // SECTIONS
    // ==========================================
    section: {
        marginBottom: spacing.xl,
    },
    
    sectionWithDivider: {
        marginBottom: spacing.xl,
        paddingBottom: spacing.xl,
        borderBottomWidth: 1,
        borderBottomColor: dark.divider,
    },

    // ==========================================
    // STATS & METRICS
    // ==========================================
    statsGrid: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        flexWrap: 'wrap',
        marginBottom: spacing.md,
    },
    
    statBox: {
        flex: 1,
        minWidth: '45%',
        alignItems: 'center',
        padding: spacing.md,
        backgroundColor: dark.background,
        borderRadius: radii.lg,
        marginHorizontal: spacing.xs,
        marginBottom: spacing.sm,
    },
    
    statIcon: {
        fontSize: 32,
        marginBottom: spacing.sm,
    },
    
    statValue: {
        fontSize: typography.h2.size,
        fontWeight: '700',
        color: dark.textPrimary,
        marginBottom: spacing.xs,
    },
    
    statLabel: {
        fontSize: typography.caption.size,
        color: dark.textSecondary,
        textAlign: 'center',
    },

    // ==========================================
    // PROGRESS BARS
    // ==========================================
    progressBarContainer: {
        height: 12,
        backgroundColor: dark.background,
        borderRadius: radii.full,
        overflow: 'hidden',
        marginBottom: spacing.sm,
    },
    
    progressBarFill: {
        height: '100%',
        backgroundColor: colors.primary500,
        borderRadius: radii.full,
    },
    
    progressLabel: {
        fontSize: typography.body.size,
        color: dark.textSecondary,
        marginBottom: spacing.sm,
    },
    
    progressValue: {
        fontSize: typography.h3.size,
        fontWeight: '700',
        color: colors.primary500,
    },

    // ==========================================
    // LIST ITEMS
    // ==========================================
    listItem: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingVertical: spacing.md,
        borderBottomWidth: 1,
        borderBottomColor: dark.divider,
    },
    
    listItemLast: {
        borderBottomWidth: 0,
    },
    
    listItemLeft: {
        flexDirection: 'row',
        alignItems: 'center',
        flex: 1,
    },
    
    listItemRight: {
        alignItems: 'flex-end',
    },
    
    listItemIcon: {
        fontSize: 28,
        marginRight: spacing.md,
    },
    
    listItemTitle: {
        fontSize: typography.body.size,
        fontWeight: '500',
        color: dark.textPrimary,
    },
    
    listItemSubtitle: {
        fontSize: typography.bodySmall.size,
        color: dark.textSecondary,
        marginTop: spacing.xs,
    },

    // ==========================================
    // BADGES & CHIPS
    // ==========================================
    badge: {
        backgroundColor: colors.primary500,
        paddingHorizontal: spacing.sm,
        paddingVertical: spacing.xs,
        borderRadius: radii.full,
        alignSelf: 'flex-start',
    },
    
    badgeText: {
        color: '#FFFFFF',
        fontSize: typography.caption.size,
        fontWeight: '600',
    },
    
    chip: {
        backgroundColor: dark.surface,
        paddingHorizontal: spacing.md,
        paddingVertical: spacing.sm,
        borderRadius: radii.full,
        borderWidth: 1,
        borderColor: dark.divider,
        marginRight: spacing.sm,
    },
    
    chipSelected: {
        backgroundColor: colors.primary500,
        borderColor: colors.primary500,
    },
    
    chipText: {
        color: dark.textSecondary,
        fontSize: typography.bodySmall.size,
        fontWeight: '500',
    },
    
    chipTextSelected: {
        color: '#FFFFFF',
    },

    // ==========================================
    // DIVIDERS
    // ==========================================
    divider: {
        height: 1,
        backgroundColor: dark.divider,
        marginVertical: spacing.md,
    },
    
    dividerThick: {
        height: 2,
        backgroundColor: dark.divider,
        marginVertical: spacing.lg,
    },

    // ==========================================
    // SPACING UTILITIES
    // ==========================================
    marginTopSm: { marginTop: spacing.sm },
    marginTopMd: { marginTop: spacing.md },
    marginTopLg: { marginTop: spacing.lg },
    marginTopXl: { marginTop: spacing.xl },
    
    marginBottomSm: { marginBottom: spacing.sm },
    marginBottomMd: { marginBottom: spacing.md },
    marginBottomLg: { marginBottom: spacing.lg },
    marginBottomXl: { marginBottom: spacing.xl },
    
    paddingMd: { padding: spacing.md },
    paddingLg: { padding: spacing.lg },
    paddingXl: { padding: spacing.xl },
});

// ==========================================
// COLOR PALETTE EXPORTS
// ==========================================

export const colorPalette = {
    // Background colors
    background: dark.background,
    surface: dark.surface,
    card: dark.card,
    
    // Text colors
    textPrimary: dark.textPrimary,
    textSecondary: dark.textSecondary,
    
    // Brand colors - Mindfulness palette
    primary: colors.primary500,        // #5B9A8B (Sage/Teal)
    primaryLight: colors.primary400,   // #6BA892
    primaryDark: colors.primary600,    // #4A7F72
    
    secondary: colors.secondary500,    // #9B8E7E (Warm Earth)
    secondaryLight: colors.secondary400, // #B3A394
    secondaryDark: colors.secondary600,  // #7A6F63
    
    accent: colors.accent500,          // #7EBAA8 (Soft Aqua)
    accentLight: colors.accent400,     // #9DD4C8
    accentDark: colors.accent600,      // #5E9A88
    
    // Semantic colors
    success: colors.success500,
    successLight: colors.success100,
    warning: colors.warning600,
    warningLight: colors.warning100,
    error: colors.error600,
    errorLight: colors.error100,
    
    // UI colors
    divider: dark.divider,
    overlay: dark.overlay,
    
    // Grays
    gray50: colors.gray50,
    gray100: colors.gray100,
    gray300: colors.gray300,
    gray500: colors.gray500,
    gray700: colors.gray700,
    gray900: colors.gray900,
};

// ==========================================
// SPACING EXPORTS
// ==========================================

export const spacingValues = spacing;

// ==========================================
// TYPOGRAPHY EXPORTS
// ==========================================

export const typographyStyles = typography;

// ==========================================
// BORDER RADIUS EXPORTS
// ==========================================

export const borderRadius = radii;

// ==========================================
// SHADOW EXPORTS
// ==========================================

export const shadowStyles = shadows;

// Export everything as default as well
export default {
    globalStyles,
    colorPalette,
    spacingValues,
    typographyStyles,
    borderRadius,
    shadowStyles,
};
