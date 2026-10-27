import assert from "node:assert/strict";
export const TICKET_REFERENCE_SOURCE="ChinaTravel固定公开参考，非实时门票售价";
// Only facts already visible to both models. Never reads expect, scores or a successful plan.
export function referenceTicketRequirements(c,frozen) {
  return frozen.cities[c.input.city].places.map(p=>({
    placeName:p.name,placeId:p.id,minimumBudget:p.ticketBudget,source:TICKET_REFERENCE_SOURCE,
  }));
}
export function verifyTicketRequirements(c,row,frozen) {
  const expected=referenceTicketRequirements(c,frozen);
  assert.deepEqual(row.observation.suppliedTicketRequirements,expected);
  if(row.observation.job)assert.deepEqual(row.observation.job.ticketRequirements,expected);
  return true;
}
