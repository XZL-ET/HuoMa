
        /* ── 全选/取消全选 ── */
        function toggleAll(source) {
            const checked = source.checked;
            document.querySelectorAll('.qr-checkbox').forEach(cb => {
                cb.checked = checked;
                const id = parseInt(cb.value);
                if (checked) selectedIds.add(id); else selectedIds.delete(id);
            });
            updateSelectedCount();
        }

        /* ── 单个勾选/取消 ── */
        function onCheck(cb) {
            const id = parseInt(cb.value);
            if (cb.checked) selectedIds.add(id); else selectedIds.delete(id);
            updateSelectedCount();
        }
        function batchSubmit(mode) {
            const checked = document.querySelectorAll('.qr-checkbox:checked');
            if (checked.length === 0) {
                alert('请先勾选要操作的活码');
                return;
            }
            const formId = mode === 'auto' ? 'batchAutoForm' : 'batchManualForm';
            const idsDiv = document.getElementById(mode === 'auto' ? 'batchAutoIds' : 'batchManualIds');
            idsDiv.innerHTML = '';
            checked.forEach(cb => {
                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = 'ids';
                input.value = cb.value;
                idsDiv.appendChild(input);
            });
            document.getElementById(formId).submit();
        }

        /* ── 批量下载二维码（企微原图） ── */
        function batchDownload() {
            const checked = document.querySelectorAll('.qr-checkbox:checked');
            if (checked.length === 0) {
                alert('请先勾选要下载的活码');
                return;
            }
            document.getElementById('downloadCount').textContent = checked.length;
            new bootstrap.Modal(document.getElementById('batchDownloadModal')).show();
        }

        function confirmBatchDownload() {
            const checked = document.querySelectorAll('.qr-checkbox:checked');
            const idsDiv = document.getElementById('batchDownloadIds');
            idsDiv.innerHTML = '';
            checked.forEach(cb => {
                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = 'ids';
                input.value = cb.value;
                idsDiv.appendChild(input);
            });
            bootstrap.Modal.getInstance(document.getElementById('batchDownloadModal')).hide();
            document.getElementById('batchDownloadForm').submit();
        }

        /* ── 打开批量配置弹窗 ── */
        function openBatchConfigModal() {
            const checked = document.querySelectorAll('.qr-checkbox:checked');
            if (checked.length === 0) {
                alert('请先勾选要配置的活码');
                return;
            }
            // 填充隐藏的 ID 字段
            const idsDiv = document.getElementById('batchConfigIds');
            idsDiv.innerHTML = '';
            checked.forEach(cb => {
                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = 'ids';
                input.value = cb.value;
                idsDiv.appendChild(input);
            });
            // 重置表单其他字段
            document.getElementById('batchConfigForm').querySelector('textarea').value = '';
            document.getElementById('batchConfigForm').querySelectorAll('select').forEach(s => s.value = '');
            // 显示弹窗
            new bootstrap.Modal(document.getElementById('batchConfigModal')).show();
        }

        /* ── 提交批量配置 ── */
        function submitBatchConfig() {
            document.getElementById('batchConfigForm').submit();
        }

        /* ── 分组树 ── */
        let treeData = null;

        function loadTree() {
            const container = document.getElementById('treeContainer');
            const loading = document.getElementById('treeLoading');
            if (!container) return;
            if (loading) loading.style.display = '';

            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 15000);

            fetch('/qrcodes/api/tree', { signal: controller.signal })
                .then(r => { clearTimeout(timeoutId); return r.json(); })
                .then(data => {
                    treeData = data;
                    renderTree(data);
                })
                .catch(err => {
                    clearTimeout(timeoutId);
                    if (err.name === 'AbortError') {
                        container.innerHTML = '<div class="text-warning text-center py-2">加载超时 <button class="btn btn-sm btn-link" onclick="loadTree()">重试</button></div>';
                    } else {
                        container.innerHTML = '<div class="text-danger text-center py-2">加载失败 <button class="btn btn-sm btn-link" onclick="loadTree()">重试</button></div>';
                    }
                    console.error('Tree load error:', err);
                });
        }

        function renderTree(nodes) {
            const container = document.getElementById('treeContainer');
            const loading = document.getElementById('treeLoading');
            if (loading) loading.style.display = 'none';

            if (!nodes || nodes.length === 0) {
                container.innerHTML = '<div class="text-muted text-center py-2">暂无数据</div>';
                return;
            }

            let html = '<ul class="list-unstyled tree-nav">';
            nodes.forEach(city => {
                const cityId = 'city-' + sanitizeId(city.name);
                html += '<li>';
                html += '<details open>';
                html += '<summary class="tree-summary">';
                html += '<span class="tree-label" onclick="filterByScope({city:\'' + esc(city.name) + '\'});return false;">';
                html += '<i class="bi bi-building me-1"></i>' + esc(city.name);
                html += '</span>';
                html += '</summary>';
                html += '<ul class="list-unstyled ms-3">';
                (city.children || []).forEach(district => {
                    const distId = 'dist-' + sanitizeId(district.name);
                    html += '<li>';
                    html += '<details>';
                    html += '<summary class="tree-summary">';
                    html += '<span class="tree-label" onclick="filterByScope({city:\'' + esc(city.name) + '\',district:\'' + esc(district.name) + '\'});return false;">';
                    html += '<i class="bi bi-geo-alt me-1"></i>' + esc(district.name);
                    html += '</span>';
                    html += '</summary>';
                    html += '<ul class="list-unstyled ms-3">';
                    (district.children || []).forEach(group => {
                        const isGroup = group.type === 'group';
                        const icon = isGroup ? 'bi-people' : 'bi-folder';
                        const label = isGroup ? esc(group.name) : esc(group.name);
                        html += '<li>';
                        if (isGroup && group.children && group.children.length > 0) {
                            html += '<details>';
                            html += '<summary class="tree-summary tree-group">';
                            html += '<span class="tree-label" onclick="filterByScope({groupId:' + group.id + '});return false;">';
                            html += '<i class="bi ' + icon + ' me-1"></i>' + label;
                            html += ' <small class="text-muted">(' + group.children.length + ')</small>';
                            html += '</span>';
                            html += '</summary>';
                            html += '<ul class="list-unstyled ms-3">';
                            group.children.forEach(qr => {
                                html += '<li><a href="#" class="tree-link tree-leaf" onclick="filterByScope({qrcodeId:' + qr.id + '});return false;">';
                                html += '<i class="bi bi-qr-code me-1"></i>' + esc(qr.name);
                                html += '</a></li>';
                            });
                            html += '</ul></details>';
                        } else {
                            html += '<span class="tree-summary tree-group" style="cursor:pointer;" onclick="filterByScope({groupId:' + (group.id || 0) + '});return false;">';
                            html += '<i class="bi ' + icon + ' me-1"></i>' + label;
                            if (isGroup) html += ' <small class="text-muted">(0)</small>';
                            html += '</span>';
                        }
                        html += '</li>';
                    });
                    html += '</ul></details>';
                    html += '</li>';
                });
                html += '</ul></details>';
                html += '</li>';
            });
            html += '</ul>';
            container.innerHTML = html;
        }

        function sanitizeId(name) {
            return name.replace(/[^a-zA-Z0-9一-鿿]/g, '_');
        }

        function esc(str) {
            if (!str) return '';
            const div = document.createElement('div');
            div.textContent = str;
            return div.innerHTML;
        }

        /* ── 通过树节点过滤表格 ── */
        function filterByScope(scope) {
            // 清除所有 active 样式
            document.querySelectorAll('.tree-link').forEach(a => a.classList.remove('active', 'fw-bold'));
            document.querySelectorAll('.tree-summary').forEach(s => s.classList.remove('tree-active'));

            // 高亮当前点击的元素（若点击的是 tree-label，高亮其父级 summary）
            if (event && event.target) {
                let el = event.target;
                if (el.classList.contains('tree-label')) {
                    el = el.closest('summary');
                }
                if (el) el.classList.add('tree-active');
            }

            // 构建筛选参数
            let params = new URLSearchParams();
            if (scope.city) params.set('city', scope.city);
            if (scope.district) params.set('district', scope.district);
            // groupId 和 qrcodeId 筛选通过前端表格过滤实现
            // 当前直接跳转到带城市/区县参数的分页列表

            if (scope.city || scope.district) {
                window.location.href = '/qrcodes?' + params.toString();
            } else if (scope.groupId) {
                // 前端按分组筛选：隐藏不匹配的行
                filterTableByGroupId(scope.groupId);
            } else if (scope.qrcodeId) {
                // 前端按活码 ID 筛选：隐藏不匹配的行
                filterTableByQrCodeId(scope.qrcodeId);
            } else {
                // "全部"：清除前端过滤
                clearTableFilter();
            }
        }

        function filterTableByGroupId(groupId) {
            window.location.href = '/qrcodes?groupId=' + encodeURIComponent(groupId);
        }

        function filterTableByQrCodeId(qrcodeId) {
            window.location.href = '/qrcodes/' + qrcodeId;
        }

        function clearTableFilter() {
            window.location.href = '/qrcodes';
        }

        /* ── 页面加载时初始化树 ── */
        document.addEventListener('DOMContentLoaded', function() {
            loadTree();
        });

        /* ── 批量操作模式 ── */
        let batchMode = false;
        const selectedIds = new Set();

        function toggleBatchMode() {
            batchMode = !batchMode;
            document.getElementById('batchToolbar').style.display = batchMode ? 'inline' : 'none';
            document.getElementById('batchModeBtn').className = batchMode ? 'btn btn-sm btn-warning' : 'btn btn-sm btn-outline-warning';
            document.getElementById('batchModeBtn').textContent = batchMode ? '🔧 退出批量' : '🔧 批量操作';
            if (!batchMode) { selectedIds.clear(); updateSelectedCount(); }
        }

        function updateSelectedCount() {
            document.getElementById('selectedCount').textContent = selectedIds.size;
        }

        function batchAction(type) {
            if (selectedIds.size === 0) { alert('请先勾选活码'); return; }
            const ids = Array.from(selectedIds);
            let url, body = 'ids=' + ids.join('&ids=');

            switch(type) {
                case 'welcome': {
                    const text = prompt('请输入新欢迎语：');
                    if (!text) return;
                    body += '&welcomeText=' + encodeURIComponent(text);
                    url = '/qrcodes/batch/welcome'; break;
                }
                case 'rotate-mode':
                    showBatchRotateModeModal(); return;
                case 'group':
                    showBatchGroupModal(); return;
                case 'status':
                    showBatchStatusModal(); return;
                case 'scene':
                    showBatchSceneModal(); return;
                default: return;
            }
            fetch(url, {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body:body})
                .then(r => r.json()).then(d => {
                    if (d.ok) { alert('成功更新 ' + d.count + ' 个活码'); location.reload(); }
                    else alert('操作失败');
                });
        }

        /* ── 批量改分组弹窗 ── */
        function showBatchGroupModal() {
            // 重置 combo-box 的显示文本和隐藏 select 的值
            const select = document.getElementById('batchGroupSelect');
            select.value = '';
            const box = select.closest('.combo-box');
            if (box) {
                const input = box.querySelector('.combo-input');
                if (input) input.value = '';
            }
            new bootstrap.Modal(document.getElementById('batchGroupModal')).show();
        }

        function submitBatchGroup() {
            const ids = Array.from(selectedIds);
            const select = document.getElementById('batchGroupSelect');
            const groupId = select.value;
            let body = 'ids=' + ids.join('&ids=');
            if (groupId) body += '&groupId=' + groupId;

            fetch('/qrcodes/batch/group', {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                body: body
            })
            .then(r => r.json()).then(d => {
                if (d.ok) {
                    bootstrap.Modal.getInstance(document.getElementById('batchGroupModal')).hide();
                    alert('成功更新 ' + d.count + ' 个活码');
                    location.reload();
                } else {
                    alert('操作失败');
                }
            });
        }

        /* ── 批量切换轮换弹窗 ── */
        function showBatchRotateModeModal() {
            document.getElementById('batchRotateModeSelect').value = 'auto';
            new bootstrap.Modal(document.getElementById('batchRotateModeModal')).show();
        }

        function submitBatchRotateMode() {
            const ids = Array.from(selectedIds);
            const mode = document.getElementById('batchRotateModeSelect').value;
            const body = 'ids=' + ids.join('&ids=') + '&mode=' + mode;

            fetch('/qrcodes/batch/rotate-mode', {
                method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body
            })
            .then(r => r.json()).then(d => {
                if (d.ok) {
                    bootstrap.Modal.getInstance(document.getElementById('batchRotateModeModal')).hide();
                    alert('成功更新 ' + d.count + ' 个活码'); location.reload();
                } else alert('操作失败');
            });
        }

        /* ── 批量切换状态弹窗 ── */
        function showBatchStatusModal() {
            document.getElementById('batchStatusSelect').value = 'active';
            new bootstrap.Modal(document.getElementById('batchStatusModal')).show();
        }

        function submitBatchStatus() {
            const ids = Array.from(selectedIds);
            const status = document.getElementById('batchStatusSelect').value;
            const body = 'ids=' + ids.join('&ids=') + '&status=' + status;

            fetch('/qrcodes/batch/status', {
                method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body
            })
            .then(r => r.json()).then(d => {
                if (d.ok) {
                    bootstrap.Modal.getInstance(document.getElementById('batchStatusModal')).hide();
                    alert('成功更新 ' + d.count + ' 个活码'); location.reload();
                } else alert('操作失败');
            });
        }

        /* ── 批量切换场景弹窗 ── */
        function showBatchSceneModal() {
            document.getElementById('batchSceneSelect').value = 'daily_push';
            new bootstrap.Modal(document.getElementById('batchSceneModal')).show();
        }

        function submitBatchScene() {
            const ids = Array.from(selectedIds);
            const scene = document.getElementById('batchSceneSelect').value;
            let body = 'ids=' + ids.join('&ids=') + '&scene=' + scene;

            fetch('/qrcodes/batch/scene', {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                body: body
            })
            .then(r => r.json()).then(d => {
                if (d.ok) {
                    bootstrap.Modal.getInstance(document.getElementById('batchSceneModal')).hide();
                    alert('成功更新 ' + d.count + ' 个活码');
                    location.reload();
                } else {
                    alert('操作失败');
                }
            });
        }
    