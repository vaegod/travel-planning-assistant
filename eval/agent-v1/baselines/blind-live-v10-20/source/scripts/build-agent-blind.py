"""One-time blind-set construction. No production code or model outputs are read to tune examples."""
import csv, hashlib, importlib.util, json, math, pathlib, re, unicodedata
from datetime import datetime, timezone
import pyarrow.parquet as pq

ROOT=pathlib.Path(__file__).resolve().parents[1]
DEST=ROOT/"eval/agent-v1/blind-v1"
SOURCE=ROOT/"eval/agent-v1/source"
def sha(data): return hashlib.sha256(data).hexdigest()
def normalized(name):
    return "".join(chr(ord(c)-0xFEE0) if 0xFF01<=ord(c)<=0xFF5E else c for c in name if not c.isspace())
def json_bytes(value): return (json.dumps(value,ensure_ascii=False,indent=2)+"\n").encode("utf8")
def write_new(path,value):
    with path.open("xb") as out: out.write(json_bytes(value))

spec=importlib.util.spec_from_file_location("v10_builder",ROOT/"scripts/build-agent-eval.py")
v10=importlib.util.module_from_spec(spec);spec.loader.exec_module(v10)
def build():
    for name in ["dataset.json","frozen-sandbox.json","seal.json"]:
        assert not (DEST/name).exists(), "Blind set already sealed; refusing overwrite"
    plan=json.loads((DEST/"plan.json").read_text(encoding="utf8"))
    old=json.loads((ROOT/"eval/agent-v1/dataset.json").read_text(encoding="utf8"))
    old_frozen=json.loads((ROOT/"eval/agent-v1/frozen-sandbox.json").read_text(encoding="utf8"))
    baseline=json.loads((ROOT/"eval/agent-v1/results"/plan["baselineRun"]/"results.json").read_text(encoding="utf8"))
    for file,digest in baseline["metadata"]["sourceFileSha256"].items():
        assert sha((ROOT/file).read_bytes())==digest, "v10 source changed: "+file
    assert sha((ROOT/"travel-java-server/target/travel-java-server-0.0.1-SNAPSHOT.jar").read_bytes())==baseline["metadata"]["jarSha256"]
    used_uids={c["provenance"].get("sourceUid") for c in old["cases"]}
    rows=list(csv.DictReader((SOURCE/"human.csv").open(encoding="utf-8-sig",newline="")))
    source_pois=pq.read_table(SOURCE/"sandbox-data_zh_attractions.parquet").to_pylist()
    cities={}
    for city in v10.CITIES:
        previous=old_frozen["cities"][city]["places"]
        old_ids={p["sourceRowId"] for p in previous}
        old_names={normalized(p["name"]) for p in previous}
        old_coords={(round(p["latitude"],6),round(p["longitude"],6)) for p in previous}
        candidates=[];names=set();coords=set()
        for p in sorted(source_pois,key=lambda p:p["id"]):
            if p["city"]!=city or p["id"] in old_ids or not p["name"] or not p["type"]:continue
            if normalized(p["name"]) in old_names or len(p["name"])>120:continue
            if not math.isfinite(p["lat"]) or not math.isfinite(p["lon"]):continue
            if p["price"] is None or not 0<=p["price"]<=100:continue
            if p["recommendmintime"] is None or not 0<p["recommendmintime"]<=3:continue
            coord=(round(p["lat"],6),round(p["lon"],6));name=normalized(p["name"])
            if coord in old_coords or coord in coords or name in names:continue
            candidates.append(p);coords.add(coord);names.add(name)
        clusters=[]
        for center in candidates:
            cluster=sorted(candidates,key=lambda p:(v10.distance(center,p),p["id"]))[:8]
            if len(cluster)<8:continue
            longest=max(v10.distance(a,b) for a in cluster for b in cluster)
            if math.ceil(max(50,round(longest*1.25))/(4.5*1000/3600)/60)>25:continue
            clusters.append((sum(v10.distance(center,p) for p in cluster),center["id"],cluster))
        assert clusters, "No structurally feasible disjoint cluster for "+city
        chosen=min(clusters,key=lambda x:(x[0],x[1]))[2]
        places=[{"id":"BL-"+city+"-"+str(p["id"]),"name":p["name"],"category":p["type"],
            "latitude":p["lat"],"longitude":p["lon"],"ticketBudget":round(p["price"],2),
            "minVisitMinutes":max(15,math.ceil(p["recommendmintime"]*60)),"sourceRowId":p["id"]} for p in chosen]
        routes=[]
        for a in places:
            for b in places:
                if a["id"]==b["id"]:continue
                straight=v10.distance({"lat":a["latitude"],"lon":a["longitude"]},{"lat":b["latitude"],"lon":b["longitude"]})
                for mode,factor,speed,overhead in [("walking",1.25,4.5,0),("transit",1.4,18,600),("driving",1.3,24,300)]:
                    meters=max(50,round(straight*factor));seconds=max(60,math.ceil(meters/(speed*1000/3600)+overhead))
                    fare=0 if mode=="walking" else 4 if mode=="transit" else None
                    routes.append({"fromId":a["id"],"toId":b["id"],"profile":mode,"meters":meters,"seconds":seconds,
                        "fare":fare,"budgetFloor":fare if fare is not None else round(5+meters/1000,2)})
        cities[city]={"places":places,"routes":routes}
    frozen={k:v for k,v in old_frozen.items() if k!="cities"}
    frozen["version"]="agent-frozen-blind-v1";frozen["cities"]=cities
    cases=[];selected=set();labels={"walking":"步行","transit":"公交（可含地铁）","driving":"自驾"}
    def task(city,days,budget,mode,index):
        return {"city":city,"days":days,"budget":budget,"travelMode":mode,
            "instruction":f"请为我安排{city}{days}天的单人旅行，目的地内预算上限{budget}元，不含往返城市交通。"
                f"出行选择{labels[mode]}。每天上午和下午各去一个不同景点，晚上可以休息；请核算所有门票、交通与每日总时长。"}
    def expect(days):
        return {"minimumAttractionsPerDay":2,"maxDailyMinutes":600,"minimumAccommodation":200*(days-1),
            "minimumFood":80*days,"minimumOther":20*days}
    for index,item in enumerate(plan["plans"]):
        city,days,mode=item["city"],item["days"],item["travelMode"]
        pool=[r for r in rows if r["target_city"]==city and int(r["days"])==days and r["uid"] not in used_uids|selected]
        assert pool, "No unused exact-duration source record for "+city+str(days)
        row=min(pool,key=lambda r:(int(r["people_number"])!=1,
            sha(f"{plan['seed']}/{city}/{days}/{r['uid']}".encode())))
        selected.add(row["uid"]);inp=task(city,days,1000*days,mode,index)
        e=expect(days)
        if index%2:
            category=cities[city]["places"][(index+days)%8]["category"];e["requiredCategory"]=category
            inp["instruction"]+=f"至少安排一个类型为【{category}】的景点。"
        cases.append({"id":f"BL{index+1:02}","split":"blind","pilot":False,"kind":"plan","expectedOutcome":"completed",
            "input":inp,"expect":e,"provenance":{"source":"ChinaTravel/human","sourceUid":row["uid"],"sourceRow":row,
                "mapped":["destination","duration"]+(["single_person"] if int(row["people_number"])==1 else []),
                "changes":["预算统一为目的地内1000元/天，不沿用原题预算及往返交通","人数统一为单人",
                    "原约束程序仅存档，不执行；除mapped外原始约束不计入评分",
                    "交通模式、每日双景点、费用/时间规则及可选类型要求为适配新增，不作为ChinaTravel官方成绩"]}})
    for item in plan["revisions"]:
        city,days,mode=item["city"],item["days"],item["travelMode"]
        target=cities[city]["places"][item["targetIndex"]]
        base=v10.witness(frozen,city,days,mode,1000*days)
        e=expect(days);e["requiredPlaceId"]=target["id"]
        instruction=f"请将第{item['day']}天的{item['slot']}调整为【{target['name']}】；未选活动内容保留，核对前后路线与时间，预算上限不变。"
        cases.append({"id":f"BL{len(cases)+1:02}","split":"blind","pilot":False,"kind":"revise","expectedOutcome":"completed",
            "input":task(city,days,1000*days,mode,0),"revision":{"day":item["day"],"slot":item["slot"],"instruction":instruction},
            "basePlan":base,"expect":e,"provenance":{"source":"project-authored","reason":"pre-registered unseen revision on disjoint POIs"}})
    for item in plan["refusals"]:
        city,days,mode=item["city"],item["days"],item["travelMode"]
        inp=task(city,days,item["budget"],mode,0)
        e={"refusalReason":item["reason"]}
        case={"id":f"BL{len(cases)+1:02}","split":"blind","pilot":False,"kind":"plan","expectedOutcome":"refused",
            "input":inp,"expect":e,"provenance":{"source":"project-authored","reason":"pre-registered unseen infeasibility example"}}
        if item["reason"]=="budget":
            minimum=200*(days-1)+100*days;assert minimum>item["budget"]
            e.update(refusalPattern="预算|费用|不足|超出|超预算",proof=f"固定住宿/餐饮/其他最低{minimum}元，大于预算{item['budget']}元")
        else:
            case["environment"]={"noTransit":True}
            inp["instruction"]+="固定环境没有任何公交连接方案，必须公交串联景点，不能用步行或自驾替代。"
            e.update(refusalPattern="公交|路线|方案|不可达|无法",proof="全部公交边无可用方案且每天要求两个不同景点")
        cases.append(case)
    assert len(cases)==20 and len(selected)==14
    dataset={k:v for k,v in old.items() if k!="cases"}
    dataset.update(version="travel-agent-blind-v1",phase="blind",seed=plan["seed"],selection=plan["selectionPolicy"],
        scope="First-use blinded adapted cases on disjoint query UIDs and POIs; not an official ChinaTravel score",
        budgetScope="单人目的地内预算，正例统一1000元/天；原预算、往返交通及未映射约束不保留",cases=cases)
    dataset_data=json_bytes(dataset);frozen_data=json_bytes(frozen)
    seal={"version":"blind-v1-seal","sealedAt":datetime.now(timezone.utc).isoformat(),"baselineRun":plan["baselineRun"],
        "planSha256":sha((DEST/"plan.json").read_bytes()),"builderSha256":sha(pathlib.Path(__file__).read_bytes()),
        "datasetSha256":sha(dataset_data),"frozenSha256":sha(frozen_data),"caseCount":20,
        "positiveCases":18,"revisionCases":4,"negativeCases":2,"sourceQueryCount":14,
        "regressionDatasetSha256":sha((ROOT/"eval/agent-v1/dataset.json").read_bytes()),
        "regressionFrozenSha256":sha((ROOT/"eval/agent-v1/frozen-sandbox.json").read_bytes()),
        "baselineResultsSha256":sha((ROOT/"eval/agent-v1/results"/plan["baselineRun"]/"results.json").read_bytes()),
        "v10SourceFileSha256":baseline["metadata"]["sourceFileSha256"],"v10JarSha256":baseline["metadata"]["jarSha256"],
        "firstUsePolicy":plan["revealPolicy"]}
    write_new(DEST/"dataset.json",dataset);write_new(DEST/"frozen-sandbox.json",frozen);write_new(DEST/"seal.json",seal)
    print(json.dumps({"cases":20,"newQueryUIDs":14,"disjointPOIs":sum(len(x["places"]) for x in cities.values()),
        "datasetSha256":seal["datasetSha256"],"productionBaseline":plan["baselineRun"]},ensure_ascii=False))
if __name__=="__main__":build()
