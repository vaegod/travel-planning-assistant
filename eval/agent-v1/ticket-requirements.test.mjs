import test from "node:test";
import assert from "node:assert/strict";
import {referenceTicketRequirements,verifyTicketRequirements,TICKET_REFERENCE_SOURCE} from "../../scripts/agent-eval/ticket-requirements.mjs";
const c={input:{city:"城市"},expect:{requiredPrice:0}};
const frozen={cities:{城市:{places:[{id:"A",name:"景点",ticketBudget:98,minVisitMinutes:180}]}}};
test("ticket budgets bind only public shared facts, with attribution independent of hidden labels",()=>{
  const expected=[{placeName:"景点",placeId:"A",minimumBudget:98,source:TICKET_REFERENCE_SOURCE}];
  assert.deepEqual(referenceTicketRequirements(c,frozen),expected);
  assert.deepEqual(referenceTicketRequirements({...c,expect:{requiredPrice:99999}},frozen),expected);
  assert(verifyTicketRequirements(c,{observation:{suppliedTicketRequirements:expected,job:{ticketRequirements:expected}}},frozen));
});
test("ticket budget audit rejects altered values, source and server echo",()=>{
  const reference=referenceTicketRequirements(c,frozen);
  for(const changed of [[{...reference[0],minimumBudget:0}],[{...reference[0],source:"unknown"}]])
    assert.throws(()=>verifyTicketRequirements(c,{observation:{suppliedTicketRequirements:changed}},frozen));
  assert.throws(()=>verifyTicketRequirements(c,{observation:{suppliedTicketRequirements:reference,job:{ticketRequirements:[]}}},frozen));
});
