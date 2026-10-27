import test from "node:test";
import assert from "node:assert/strict";
import {referenceVisitRequirements,verifyVisitRequirements} from "../../scripts/agent-eval/visit-requirements.mjs";
const frozen={cities:{城市:{places:[{id:"A",name:"地点A",minVisitMinutes:180,category:"类型"}]}}};
const c={input:{city:"城市"},expect:{requiredPlaceId:"do-not-read"}};
test("visit constraints mirror public shared facts and ignore hidden expected answers",()=>{
  const expected=[{placeName:"地点A",placeId:"A",minimumMinutes:180}];
  assert.deepEqual(referenceVisitRequirements(c,frozen),expected);
  assert.deepEqual(referenceVisitRequirements({...c,expect:{minimumVisit:1}},frozen),expected);
  assert(verifyVisitRequirements(c,{observation:{suppliedVisitRequirements:expected,job:{visitRequirements:expected}}},frozen));
});
test("visit constraint audit rejects changed caller bounds or server echoes",()=>{
  const reference=referenceVisitRequirements(c,frozen);
  const changed=[{...reference[0],minimumMinutes:15}];
  assert.throws(()=>verifyVisitRequirements(c,{observation:{suppliedVisitRequirements:changed}},frozen));
  assert.throws(()=>verifyVisitRequirements(c,{observation:{suppliedVisitRequirements:reference,job:{visitRequirements:changed}}},frozen));
});
