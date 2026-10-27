"""Reproducible ChinaTravel adaptation. Never executes upstream constraint programs."""
import argparse, csv, hashlib, json, math, pathlib, re
import pyarrow.parquet as pq

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEST = ROOT / "eval/agent-v1"
SOURCE = DEST / "source"
SEED = 20261024
CITIES = ["杭州", "成都", "北京", "上海", "南京", "武汉", "广州", "深圳", "重庆", "苏州"]

def sha(data): return hashlib.sha256(data).hexdigest()
def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
def distance(a, b):
    lat = math.radians((a["lat"] + b["lat"]) / 2)
    return math.hypot((a["lat"]-b["lat"])*111195, (a["lon"]-b["lon"])*111195*math.cos(lat))

def fixtures():
    rows = pq.read_table(SOURCE/"sandbox-data_zh_attractions.parquet").to_pylist()
    cities = {}
    for city in CITIES:
        candidates = [r for r in rows if r["city"] == city and r["name"] and r["type"]
                      and math.isfinite(r["lat"]) and math.isfinite(r["lon"])
                      and r["price"] is not None and 0 <= r["price"] <= 100
                      and r["recommendmintime"] is not None and 0 < r["recommendmintime"] <= 3]
        assert len(candidates) >= 8, city
        coordinates = set()
        unique = []
        for row in sorted(candidates, key=lambda r:r["id"]):
            key=(round(row["lat"],6),round(row["lon"],6))
            if key not in coordinates:
                unique.append(row);coordinates.add(key)
        candidates=unique
        assert len(candidates)>=8,city
        # Choose a dense cluster by geometry, before observing model outputs.
        center = min(candidates, key=lambda c: (sum(sorted(distance(c, x) for x in candidates)[:8]), c["id"]))
        chosen = sorted(candidates, key=lambda c: (distance(c, center), c["id"]))[:8]
        places = []
        seen = set()
        for row in chosen:
            assert row["name"] not in seen; seen.add(row["name"])
            places.append({
                "id": "CT-" + city + "-" + str(row["id"]), "name": row["name"], "category": row["type"],
                "latitude": row["lat"], "longitude": row["lon"], "ticketBudget": round(row["price"],2),
                "minVisitMinutes": max(15, math.ceil(row["recommendmintime"] * 60)),
                "sourceRowId": row["id"],
            })
        routes = []
        for a in places:
            for b in places:
                if a["id"] == b["id"]: continue
                straight = distance({"lat": a["latitude"], "lon": a["longitude"]},
                                    {"lat": b["latitude"], "lon": b["longitude"]})
                for mode, factor, speed, overhead in [("walking",1.25,4.5,0),("transit",1.4,18,600),("driving",1.3,24,300)]:
                    meters = max(50, round(straight * factor))
                    seconds = max(60, math.ceil(meters / (speed*1000/3600) + overhead))
                    fare = 0 if mode == "walking" else 4 if mode == "transit" else None
                    routes.append({"fromId":a["id"],"toId":b["id"],"profile":mode,"meters":meters,
                                   "seconds":seconds,"fare":fare,
                                   "budgetFloor": fare if fare is not None else round(5+meters/1000,2)})
        cities[city] = {"places":places,"routes":routes}
    return {"version":"agent-frozen-sandbox-v1","queriedAt":"2026-10-24T00:00:00Z",
            "poiOrigin":"Pinned ChinaTravel-Sandbox Chinese attractions",
            "routeOrigin":"Synthetic deterministic geometry-based replay; NOT Amap observations",
            "routeFormula":{"walking":"distance*1.25 at 4.5km/h","transit":"distance*1.4 at 18km/h + 600s",
                            "driving":"distance*1.3 at 24km/h + 300s"},
            "costAssumptions":{"accommodationPerNight":200,"foodPerDay":80,"otherPerDay":20},
            "cities":cities}

def budget(row):
    found = re.search(r"预算(?:为|是|在|不超过|不多于)?\s*(\d+(?:\.\d+)?)",row["nature_language"])
    if not found: return None
    value=float(found.group(1))
    return value if 100 <= value <= 1000000 else None

def plan_input(city, days, limit, mode, category=None):
    detail = f"单人目的地内{days}天，总预算上限{limit:g}元，不含往返目的地交通。每天上午、下午各安排一个不同景点，晚上可以住宿地休息。"
    if category: detail += f"全行程至少有一个类型为【{category}】的景点。"
    return {"city":city,"days":days,"budget":limit,"travelMode":mode,"instruction":detail}

def witness(frozen, city, days, mode, budget_value):
    env=frozen["cities"][city]; places=env["places"]; used=places[:days*2]; daily=[]
    fare_total=0
    for i in range(days):
        slots={}
        for s,name in enumerate(["morning","afternoon"]):
            place=used[i*2+s]
            slot={"spot":place["name"],"placeId":place["id"],"latitude":place["latitude"],
                  "longitude":place["longitude"],"sourceUrl":"https://example.invalid/eval/"+place["id"],
                  "queriedAt":frozen["queriedAt"],"visitMinutes":max(90,place["minVisitMinutes"]),
                  "travelMinutes":0,"estimatedCost":place["ticketBudget"],"duration":f"{max(90,place['minVisitMinutes'])}分钟",
                  "description":"固定评测起始行程","ticket":"固定沙箱门票","transportation":"首站交通不计入固定路段"}
            if s:
                route=next(r for r in env["routes"] if r["fromId"]==used[i*2]["id"] and r["toId"]==place["id"] and r["profile"]==mode)
                slot["travelMinutes"]=math.ceil(route["seconds"]/60);fare_total+=route["budgetFloor"]
                slot["route"]={k:route[k] for k in ["fromId","toId","profile","meters","seconds"]}
                slot["route"].update(source="eval-frozen-v1",queriedAt=frozen["queriedAt"],estimatedFare=route["fare"])
                slot["transportation"]="固定沙箱路线"
            slots[name]=slot
        slots["evening"]={"spot":"酒店自由休息","rest":True,"visitMinutes":60,"travelMinutes":0,
                          "estimatedCost":0,"duration":"60分钟","ticket":"无新增门票","transportation":"无新增交通",
                          "description":"休息"}
        daily.append({"day":i+1,"date":f"第{i+1}天",**slots})
    costs={"accommodation":200*(days-1),"food":80*days,"transportation":round(fare_total+30,2),
           "tickets":round(sum(p["ticketBudget"] for p in used),2),"other":20*days}
    total=round(sum(costs.values()),2)
    assert total <= budget_value
    return {"success":True,"city":city,"days":days,"travelMode":mode,"totalBudget":budget_value,
            "dailyItinerary":daily,"budgetBreakdown":costs,"estimatedTotal":total,"remainingBudget":budget_value-total}

def build(phase):
    frozen=fixtures()
    rows=list(csv.DictReader((SOURCE/"easy.csv").open(encoding="utf-8-sig",newline="")))
    pool=rows
    cases=[]
    for day in [1,2,3]:
        for i,city in enumerate(CITIES):
            matching=[r for r in pool if r["target_city"]==city and int(r["days"])==day]
            assert matching, (city,day)
            row=min(matching,key=lambda r:(int(r["people_number"])!=1,budget(r) is None,r["uid"]))
            mode=["walking","transit","driving"][(i+day-1)%3]
            category=frozen["cities"][city]["places"][(i+day)%8]["category"] if day>1 else None
            index=len(cases)+1
            cases.append({
                "id":f"CT{index:02}","split":"development" if index<=24 else "holdout","pilot":index<=10,
                "kind":"plan","expectedOutcome":"completed",
                "input":plan_input(city,day,budget(row) or 1000*day,mode,category),
                "expect":{"minimumAttractionsPerDay":2,"requiredCategory":category,"maxDailyMinutes":600,
                          "minimumAccommodation":200*(day-1),"minimumFood":80*day,"minimumOther":20*day},
                "provenance":{"source":"ChinaTravel/easy","sourceUid":row["uid"],"sourceRow":row,
                    "mapped":["destination","duration"]+(["numeric_budget"] if budget(row) else [])
                             +(["single_person"] if int(row["people_number"])==1 else []),
                    "changes":[f"原始同行人数{row['people_number']}，适配任务统一为单人；评分不声称等同原题",
                               "移除出发地及往返目的地交通",
                               ("原预算数字保留，范围改为目的地内支出" if budget(row) else "原题没有可直接提取的数字预算，显式新增1000元/天预算上限"),
                               "原始约束程序仅作来源存档，不执行；除mapped列出的维度外，原始约束不纳入适配评分",
                               "新增三种交通模式、每日两个景点、夜间可休息、固定费用假设",
                               "如有requiredCategory，该约束为适配后新增，不宣称来自原题"]}})
    if phase=="full":
        for i in range(8):
            city=CITIES[i];mode=["walking","transit","driving"][i%3]
            base=witness(frozen,city,2,mode,2000)
            selected_day=1+i%2;slot=["morning","afternoon","day"][i%3]
            target=frozen["cities"][city]["places"][4+i%2]
            instruction=f"将所选时段改成【{target['name']}】，其他时段内容保持原样；核对实际相邻路线与时间，预算上限不变。"
            cases.append({"id":f"B{i+1:02}","split":"development" if i<6 else "holdout","pilot":False,
                "kind":"revise","expectedOutcome":"completed",
                "input":plan_input(city,2,2000,mode),
                "revision":{"day":selected_day,"slot":slot,"instruction":instruction},
                "basePlan":base,
                "expect":{"minimumAttractionsPerDay":2,"requiredPlaceId":target["id"],"maxDailyMinutes":600,
                          "minimumAccommodation":200,"minimumFood":160,"minimumOther":40},
                "provenance":{"source":"project-authored","reason":"局部修改与前后路段一致性"}})
        low=plan_input("杭州",3,100,"transit")
        cases.append({"id":"B09","split":"holdout","pilot":False,"kind":"plan","expectedOutcome":"refused",
            "input":low,"expect":{"refusalReason":"budget","refusalPattern":"预算|费用|不足|超出|超预算",
                "proof":"最低住宿400+餐饮240+其他60=700元，已超过100元；未含门票与交通"},
            "provenance":{"source":"project-authored","reason":"可证明不可行预算，单独统计正确拒绝"}})
        none=plan_input("成都",1,1000,"transit")
        none["instruction"]+="必须使用公交连接上午和下午两个景点；固定环境中没有可用公交方案，不得用步行替代。"
        cases.append({"id":"B10","split":"holdout","pilot":False,"kind":"plan","expectedOutcome":"refused",
            "environment":{"noTransit":True},"input":none,
            "expect":{"refusalReason":"route","refusalPattern":"公交|路线|方案|不可达|无法",
                      "proof":"所有有向公交路段均无方案，任务要求两个不同景点以公交连接"},
            "provenance":{"source":"project-authored","reason":"无公交方案且不得改用其他交通"}})
    assert len({c["id"] for c in cases})==len(cases)
    selected=cases if phase=="full" else cases[:10]
    for c in selected:
        if c["expectedOutcome"]=="completed" and c["kind"]=="plan":
            # Feasibility witness generated from frozen facts, never shown to a model.
            env=frozen["cities"][c["input"]["city"]]
            ticket_floor=sum(sorted(p["ticketBudget"] for p in env["places"])[:2*c["input"]["days"]])
            assert c["input"]["budget"]>=sum(c["expect"][k] for k in ["minimumAccommodation","minimumFood","minimumOther"])+ticket_floor
    query_meta=json.loads((SOURCE/"ChinaTravel-metadata.json").read_text(encoding="utf8"))
    sandbox_meta=json.loads((SOURCE/"ChinaTravel-Sandbox-metadata.json").read_text(encoding="utf8"))
    source_files={p.name:sha(p.read_bytes()) for p in sorted(SOURCE.iterdir()) if p.is_file()}
    dataset={"version":"travel-agent-eval-v1","phase":phase,"seed":SEED,
        "scope":"ChinaTravel-adapted destination-only single-person offline evaluation; NOT official ChinaTravel scores",
        "queryRevision":query_meta["sha"],"sandboxRevision":sandbox_meta["sha"],"sourceFileSha256":source_files,
        "selection":"Earliest UID per city/duration, preferring single-person then extractable numeric budget; other party sizes explicitly normalized to one person",
        "sourceLicense":"CC-BY-4.0","budgetScope":"单人目的地内预算；往返目的地交通剔除；有数字上限则保留，否则显式新增1000元/天",
        "cases":selected}
    write(DEST/"frozen-sandbox.json",frozen)
    write(DEST/("pilot.json" if phase=="pilot" else "dataset.json"),dataset)
    print(json.dumps({"phase":phase,"cases":len(selected),"split":{s:sum(c["split"]==s for c in selected) for s in ["development","holdout"]},
                      "sourceRevision":query_meta["sha"],"fixtureSha256":sha((DEST/"frozen-sandbox.json").read_bytes())},ensure_ascii=False))
if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--phase",choices=["pilot","full"],default="pilot")
    build(parser.parse_args().phase)

