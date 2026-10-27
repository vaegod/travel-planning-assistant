import test from "node:test";
import assert from "node:assert/strict";
import {parseVisitRequirements} from "../src/utils/visitRequirements.ts";
test("optional visit bounds parse names with spaces, digits and minutes",()=>{
  assert.deepEqual(parseVisitRequirements("故宫博物院 180分钟\n洪崖洞重逢1980街区 90\nNew York Museum 120分钟"),[
    {placeName:"故宫博物院",minimumMinutes:180},{placeName:"洪崖洞重逢1980街区",minimumMinutes:90},
    {placeName:"New York Museum",minimumMinutes:120}]);
  assert.deepEqual(parseVisitRequirements("\n "),[]);
});
test("visit bounds reject out of range, ambiguous syntax and duplicate normalized names",()=>{
  for(const text of ["故宫博物院 0分钟","故宫博物院 361分钟","故宫博物院 很久","故宫博物院 90\n故 宫博物院 120"])
    assert.throws(()=>parseVisitRequirements(text));
});
