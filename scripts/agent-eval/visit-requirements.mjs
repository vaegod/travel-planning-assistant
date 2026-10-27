import assert from "node:assert/strict";

// These are the exact public reference fields already present in both groups' shared context.
// Never uses case.expect, expected outcomes, successful plans or scoring results.
export function referenceVisitRequirements(c,frozen){
  return frozen.cities[c.input.city].places.map(p=>({placeName:p.name,placeId:p.id,minimumMinutes:p.minVisitMinutes}));
}
export function verifyVisitRequirements(c,row,frozen){
  const reference=referenceVisitRequirements(c,frozen);
  assert.deepEqual(row.observation.suppliedVisitRequirements,reference,"Caller constraints differ from shared public reference facts");
  if(row.observation.job)assert.deepEqual(row.observation.job.visitRequirements,reference,"Server did not retain the supplied visit constraints");
  return true;
}
