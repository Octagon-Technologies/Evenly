/**
 * The app's expense categories as the group page draws them: `ExpenseCategory` ids, the icon from
 * `GroupBalancesMapping.categoryIcon`, and the tint from `ui/theme/CategoryColors.kt`. An unknown id
 * falls back to Other, as `ExpenseCategory.fromId(...) ?: OTHER` does.
 */
export type CategoryIcon = 'food' | 'cart' | 'car' | 'bed' | 'ticket' | 'tag' | 'bolt' | 'sparkle' | 'wallet';

const CATEGORIES: Record<string, { icon: CategoryIcon; color: string }> = {
  food: { icon: 'food', color: '#38BDF8' },
  groceries: { icon: 'cart', color: '#2DD4BF' },
  transport: { icon: 'car', color: '#818CF8' },
  lodging: { icon: 'bed', color: '#2563EB' },
  entertainment: { icon: 'ticket', color: '#A78BFA' },
  shopping: { icon: 'tag', color: '#F59E0B' },
  utilities: { icon: 'bolt', color: '#60A5FA' },
  health: { icon: 'sparkle', color: '#F472B6' },
  other: { icon: 'wallet', color: '#94A3B8' },
};

export function category(id: string | null | undefined): { icon: CategoryIcon; color: string } {
  return CATEGORIES[id ?? ''] ?? CATEGORIES.other;
}

/** The tile fill: the category colour at 14% alpha, as `categoryColor.copy(alpha = 0.14f)`. */
export function tint(hex: string): string {
  return `${hex}24`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/** `GroupExpensesMapping.dayLabel`: "Today", else "Wed, May 22". */
export function dayLabel(date: string, today: string): string {
  if (date === today) return 'Today';
  const d = new Date(`${date}T00:00:00`);
  if (Number.isNaN(d.getTime())) return date;
  return `${DAYS[d.getDay()]}, ${MONTHS[d.getMonth()]} ${d.getDate()}`;
}
