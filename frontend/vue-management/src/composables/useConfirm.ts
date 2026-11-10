// 刪除這類危險操作的確認視窗；按確定回 true，取消或關閉回 false
// 獨立成一個函式，測試時可以直接 mock，不必操作 ElMessageBox 的 DOM
export async function confirmDanger(message: string, title = '請確認'): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, {
      type: 'warning',
      confirmButtonText: '確定',
      cancelButtonText: '取消',
    })
    return true
  } catch {
    return false
  }
}
