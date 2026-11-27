// The Android app's palette (android/app/src/main/res/values*/colors.xml), light and dark.
import { useColorScheme } from 'react-native';

const light = {
  window: '#ECE7DA', surface: '#F5F1E6', ink: '#262220', muted: '#5E574D', faint: '#6D675B', line: '#D8D1C0',
  chip: '#E4DCCB', quote: '#4A453D', link: '#1B5FAB', ok: '#2E7D4F', err: '#B3261E', warn: '#B25A1E', clay: '#A15C48',
  userBg: '#262220', userText: '#F5F1E6', codeBg: '#262220', codeText: '#ECE7DA', progBg: '#E7E0D0', banner: '#1E6B4B',
};
const dark: typeof light = {
  window: '#1A1712', surface: '#262220', ink: '#ECE7DA', muted: '#A89E8C', faint: '#968E7D', line: '#3A352E',
  chip: '#35302A', quote: '#C9C0AC', link: '#7FB3E8', ok: '#6FCF97', err: '#E08073', warn: '#E8A06A', clay: '#C97B63',
  userBg: '#ECE7DA', userText: '#1A1712', codeBg: '#0F0D0A', codeText: '#ECE7DA', progBg: '#211D18', banner: '#1F5A42',
};
export type Palette = typeof light;

export function usePalette(): Palette {
  return useColorScheme() === 'dark' ? dark : light;
}
export const palettes = { light, dark };
