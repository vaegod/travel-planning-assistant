export const travelModes = [
  { value: "walking", label: "步行", description: "适合相邻景点" },
  { value: "transit", label: "公交", description: "公交、地铁与换乘" },
  { value: "driving", label: "自驾", description: "按驾车路线安排" },
] as const;
export type TravelMode = (typeof travelModes)[number]["value"];
export function normalizeTravelMode(value: unknown): TravelMode {
  return travelModes.find(mode => mode.value === value)?.value || "walking";
}
export function travelModeLabel(value: unknown): string {
  return travelModes.find(mode => mode.value === value)?.label || "未设置";
}
