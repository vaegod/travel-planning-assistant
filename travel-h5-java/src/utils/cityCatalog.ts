import { areaList } from "@vant/area-data";

export interface Destination {
  code: string;
  name: string;
  provinceCode: string;
  provinceName: string;
  fullName: string;
}
export interface CityOption {
  text: string;
  value: string;
  children?: CityOption[];
}

// Versioned local data: choosing a destination does not make a network request.
export const destinations: Destination[] = [];
export const cityOptions: CityOption[] = Object.entries(
  areaList.province_list,
).map(([provinceCode, provinceName]) => {
  const children: CityOption[] = [];
  const included = new Set<string>();
  const add = (code: string, name: string) => {
    if (included.has(code)) return;
    included.add(code);
    destinations.push({
      code,
      name,
      provinceCode,
      provinceName,
      fullName: name === provinceName ? name : provinceName + name,
    });
    children.push({ text: name, value: code });
  };
  for (const [code, name] of Object.entries(areaList.city_list)) {
    if (code.slice(0, 2) !== provinceCode.slice(0, 2)) continue;
    if (/^(省|自治区)直辖/.test(name)) {
      for (const [countyCode, countyName] of Object.entries(
        areaList.county_list,
      ))
        if (countyCode.slice(0, 4) === code.slice(0, 4))
          add(countyCode, countyName);
    } else add(code, name);
  }
  // County-level cities (e.g. Yiwu, Kunshan, Dunhuang) are also city destinations.
  for (const [code, name] of Object.entries(areaList.county_list))
    if (code.slice(0, 2) === provinceCode.slice(0, 2) && name.endsWith("市"))
      add(code, name);
  return { text: provinceName, value: provinceCode, children };
});

const byCode = new Map(destinations.map((city) => [city.code, city]));
export const destinationByCode = (code: string) => byCode.get(code);
export function findDestination(value: string) {
  return destinations.find(
    (city) =>
      city.fullName === value.trim() ||
      city.name === value.trim() ||
      city.name.replace(/市$/, "") === value.trim(),
  );
}
export function searchDestinations(query: string) {
  const tokens = query.trim().split(/\s+/).filter(Boolean);
  if (!tokens.length) return [];
  return destinations.filter((city) =>
    tokens.every((token) => city.fullName.includes(token)),
  );
}
