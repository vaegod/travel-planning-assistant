export function validateTrip({ city, budget, days }) {
  if (typeof city !== 'string' || !city.trim()) return '请选择目的地'
  if (city.length > 50) return '城市不能超过50个字符'
  if (!Number.isFinite(Number(budget)) || Number(budget) < 100 || Number(budget) > 1000000) {
    return '预算必须为100至1000000元之间的有效数字'
  }
  if (!Number.isInteger(Number(days)) || Number(days) < 1 || Number(days) > 30) return '天数必须是1至30之间的整数'
  return ''
}
