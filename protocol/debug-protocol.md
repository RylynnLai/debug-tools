# Debug Protocol

传输层使用 TCP，每条消息一行 JSON。

## Request

```json
{"id":"1","type":"get_view_tree"}
```

## Response

```json
{"id":"1","type":"view_tree","success":true,"payload":{"activity":"MainActivity","tree":{}}}
```

## Commands

- `hello`
- `get_view_tree`
- `get_view_preview`
- `update_view_props`
- `list_mocks`
- `set_mock`
- `clear_mock`
- `list_watches`

## watch_list payload

```json
{
  "retainedObjectCount": 1,
  "items": [
    {
      "label": "activity:MainActivity",
      "retained": true,
      "className": "MainActivity",
      "location": "MainActivity.binding",
      "retainedDurationMs": 8234,
      "source": "leakcanary",
      "traceSummary": "MainActivity.binding -> RecyclerView.mContext",
      "analysisTimestampMs": 1775320000000
    }
  ]
}
```

`source=leakcanary` 表示该条目已被 LeakCanary 堆分析命中，`traceSummary` 为引用链摘要，`analysisTimestampMs` 为分析完成时间（epoch ms）。

## view_preview payload

```json
{
  "activity": "MainActivity",
  "format": "jpeg",
  "width": 1080,
  "height": 2244,
  "imageBase64": "..."
}
```

## view_tree node fields (partial)

```json
{
  "path": "0.1.2",
  "id": "title",
  "idValue": 2131230890,
  "className": "androidx.appcompat.widget.AppCompatTextView",
  "visibility": "VISIBLE",
  "enabled": true,
  "clickable": false,
  "focusable": false,
  "alpha": 1.0,
  "label": "Profile",
  "contentDescription": "profile-title",
  "hint": "",
  "bgColor": "#FF112233",
  "textColor": "#FF101010",
  "textSizeSp": 16.0,
  "cornerRadiusPx": 12.0,
  "iconHint": "text-icon",
  "marginLeft": 16,
  "marginTop": 8,
  "marginRight": 16,
  "marginBottom": 8,
  "paddingLeft": 12,
  "paddingTop": 8,
  "paddingRight": 12,
  "paddingBottom": 8,
  "imageBase64": ""
}
```

`className` is the full source class name (FQCN). In vector-only 3D mode, node `imageBase64` can be empty.

## update_view_props payload

```json
{
  "path": "0.1.2",
  "label": "New Title",
  "contentDescription": "new-desc",
  "hint": "type here",
  "color": "#FF00AAFF",
  "textColor": "#FFFFFFFF",
  "textSizeSp": "18",
  "alpha": "0.9",
  "marginLeft": 12,
  "marginTop": 8,
  "marginRight": 12,
  "marginBottom": 8,
  "paddingLeft": 10,
  "paddingTop": 6,
  "paddingRight": 10,
  "paddingBottom": 6
}
```

## set_mock payload

```json
{
  "method": "GET",
  "path": "/api/profile",
  "statusCode": 200,
  "body": "{\"name\":\"debug-user\"}",
  "headers": {
    "Content-Type": "application/json"
  }
}
```
