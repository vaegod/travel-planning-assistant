import test from "node:test";
import assert from "node:assert/strict";
import {parseTicketRequirements} from "../src/utils/ticketRequirements.ts";
test("ticket references preserve names, decimal amounts and their user-provided source",()=>{
  assert.deepEqual(parseTicketRequirements("雨林植物园 98元\nNew York Museum 39.90\n免费参考点 0元"),[
    {placeName:"雨林植物园",minimumBudget:98,source:"用户提供的门票预算参考"},
    {placeName:"New York Museum",minimumBudget:39.9,source:"用户提供的门票预算参考"},
    {placeName:"免费参考点",minimumBudget:0,source:"用户提供的门票预算参考"}]);
  assert.deepEqual(parseTicketRequirements("\n "),[]);
});
test("ticket references reject negative, imprecise, excessive and duplicate values",()=>{
  for(const text of ["地点 -1元","地点 1.001元","地点 1000001元","地点 免费","地点 10\n地 点 20"])
    assert.throws(()=>parseTicketRequirements(text));
});
