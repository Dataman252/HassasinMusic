/**
 * Hassasin Music: tester reports into this Google Sheet.
 *
 * Set up (once):
 *  1. Open the Google Sheet that should collect reports.
 *  2. Extensions → Apps Script, paste this file, Save.
 *  3. Deploy → New deployment → type "Web app".
 *     Execute as: Me.  Who has access: Anyone.  Deploy, allow access.
 *  4. Copy the Web app URL (ends in /exec) and put it in Reports.URL in the app.
 *
 * Each report becomes one row on the "Reports" tab.
 */
function doPost(e) {
  var d;
  try { d = JSON.parse(e.postData.contents); } catch (err) { return ContentService.createTextOutput('bad'); }
  if (d.app !== 'HassasinMusic') return ContentService.createTextOutput('ignored');
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sh = ss.getSheetByName('Reports');
  if (!sh) {
    sh = ss.insertSheet('Reports');
    sh.appendRow(['Received', 'Kind', 'Version', 'Phone', 'Android', 'Output', 'Error', 'Report']);
    sh.setFrozenRows(1);
  }
  sh.appendRow([new Date(), d.kind, d.version, d.phone, d.android, d.output, String(d.error || '').slice(0, 45000), String(d.report || '').slice(0, 45000)]);
  return ContentService.createTextOutput('ok');
}
