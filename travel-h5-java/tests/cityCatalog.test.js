import test from "node:test";
import assert from "node:assert/strict";
import {
  cityOptions,
  destinations,
  destinationByCode,
  findDestination,
  searchDestinations,
} from "../src/utils/cityCatalog.ts";
test("local catalog covers all 34 provincial-level entries with selectable children", () => {
  assert.equal(cityOptions.length, 34);
  for (const province of cityOptions) {
    assert(province.children.length > 0);
    for (const child of province.children)
      assert(destinationByCode(child.value));
  }
  assert(destinations.length > 350);
  assert.equal(
    new Set(destinations.map((c) => c.code)).size,
    destinations.length,
  );
});
test("municipalities, autonomous prefectures and Hong Kong Macao Taiwan are included", () => {
  for (const name of [
    "北京市",
    "天津市",
    "上海市",
    "重庆市",
    "大理白族自治州",
    "台北市",
    "香港岛",
    "澳门半岛",
  ])
    assert(findDestination(name), name);
  assert.equal(findDestination("北京市").fullName, "北京市");
  assert.equal(findDestination("香港岛").fullName, "香港特别行政区香港岛");
});
test("province-administered groups expand to actual destinations instead of placeholder groups", () => {
  assert(!destinations.some((c) => /^(省|自治区)直辖/.test(c.name)));
  for (const name of ["济源市", "仙桃市", "五指山市", "石河子市"])
    assert(findDestination(name), name);
});

test("county-level city destinations are selectable without duplicating existing city codes", () => {
  for (const name of [
    "义乌市",
    "昆山市",
    "敦煌市",
    "满洲里市",
    "大理市",
    "香格里拉市",
  ])
    assert(findDestination(name), name);
  assert.equal(
    new Set(destinations.map((city) => city.code)).size,
    destinations.length,
  );
});
test("search supports city names, province names and multiple keywords", () => {
  assert(searchDestinations("西安").some((c) => c.name === "西安市"));
  const result = searchDestinations("浙江 杭州");
  assert.equal(result.length, 1);
  assert.equal(result[0].fullName, "浙江省杭州市");
  assert(
    searchDestinations("新疆").every(
      (c) => c.provinceName === "新疆维吾尔自治区",
    ),
  );
  assert.deepEqual(searchDestinations("不存在的测试目的地"), []);
  assert.deepEqual(searchDestinations("  "), []);
});
test("popular short names can preselect the corresponding destination and unknown codes are safe", () => {
  assert.equal(findDestination("杭州").name, "杭州市");
  assert.equal(findDestination("浙江省杭州市").name, "杭州市");
  assert.equal(destinationByCode("missing"), undefined);
  for (const city of destinations) assert(city.fullName.length <= 50);
});
