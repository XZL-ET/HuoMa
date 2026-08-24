/**
 * 数据看板 — 图表、漏斗、排行榜渲染。
 * 与自动刷新脚本协作，提供趋势图、漏斗图和排行榜的初始加载与时间切换联动。
 */
(function () {
    'use strict';

    var currentRange = localStorage.getItem('dashboard_timerange') || 'today';
    var chartAddAlerts = null;
    var chartPool = null;
    var hasChartJs = (typeof Chart !== 'undefined');
    var chartLoadRetried = false;

    // ── Chart.js CDN 回退（BootCDN 不可用时切 jsDelivr） ──
    function ensureChartJs(callback) {
        if (hasChartJs) { callback(); return; }
        if (chartLoadRetried) {
            // 已重试过仍失败，显示降级提示
            showChartFallback();
            return;
        }
        chartLoadRetried = true;
        var s = document.createElement('script');
        s.src = 'https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js';
        s.onload = function () {
            hasChartJs = true;
            callback();
        };
        s.onerror = function () {
            showChartFallback();
        };
        document.head.appendChild(s);
    }

    function showChartFallback() {
        var c1 = document.getElementById('chartAddAlerts');
        var c2 = document.getElementById('chartPool');
        if (c1) c1.parentElement.innerHTML = '<div class="text-muted text-center py-5">图表库加载失败，请刷新页面重试</div>';
        if (c2) c2.parentElement.innerHTML = '<div class="text-muted text-center py-5">图表库加载失败，请刷新页面重试</div>';
    }

    // ── 初始化 ──────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        // 高亮当前时间范围按钮
        highlightRangeBtn(currentRange);
        // 初始加载
        loadTrends(currentRange === 'today' ? 7 : parseInt(currentRange.replace('days', '')));
        loadFunnels();
        loadLeaderboards(currentRange);

        // 时间范围切换
        document.querySelectorAll('.btn-range').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var r = this.getAttribute('data-range');
                if (r === currentRange) return;
                currentRange = r;
                localStorage.setItem('dashboard_timerange', r);
                highlightRangeBtn(r);
                // 更新卡片（range 参数改变）
                if (typeof fetchStats === 'function') fetchStats(r);
                loadTrends(r === 'today' ? 7 : parseInt(r.replace('days', '')));
                loadLeaderboards(r);
            });
        });

        // 导出弹窗默认日期
        var today = new Date();
        var weekAgo = new Date(today.getTime() - 7 * 86400000);
        var startEl = document.getElementById('exportStart');
        var endEl = document.getElementById('exportEnd');
        if (startEl) startEl.value = formatDate(weekAgo);
        if (endEl) endEl.value = formatDate(today);
    });

    // ── 趋势图 ──────────────────────────────────

    function loadTrends(days) {
        fetch('/dashboard/api/trends?days=' + days)
            .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
            .then(renderTrends)
            .catch(function (e) { logApiError('趋势图', e); });
    }

    function renderTrends(d) {
        // Chart.js 不可用时尝试备用 CDN 加载
        if (!hasChartJs) {
            ensureChartJs(function () { renderTrends(d); });
            return;
        }
        // 客户 + 告警双轴折线图
        var ctx1 = document.getElementById('chartAddAlerts');
        if (ctx1) {
            if (chartAddAlerts) chartAddAlerts.destroy();
            chartAddAlerts = new Chart(ctx1, {
                type: 'line',
                data: {
                    labels: d.labels,
                    datasets: [
                        {
                            label: '新增客户',
                            data: d.totalAdd,
                            borderColor: '#0d6efd',
                            backgroundColor: 'rgba(13,110,253,0.1)',
                            fill: true,
                            tension: 0.3,
                            yAxisID: 'y'
                        },
                        {
                            label: '告警',
                            data: d.totalAlert,
                            borderColor: '#dc3545',
                            backgroundColor: 'rgba(220,53,69,0.1)',
                            fill: true,
                            tension: 0.3,
                            yAxisID: 'y1'
                        }
                    ]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    interaction: { mode: 'index', intersect: false },
                    plugins: {
                        legend: { position: 'top' }
                    },
                    scales: {
                        y: {
                            type: 'linear',
                            position: 'left',
                            title: { display: true, text: '客户数' },
                            beginAtZero: true
                        },
                        y1: {
                            type: 'linear',
                            position: 'right',
                            title: { display: true, text: '告警数' },
                            grid: { drawOnChartArea: false },
                            beginAtZero: true
                        }
                    }
                }
            });
        }

        // 池利用堆叠柱状图
        var ctx2 = document.getElementById('chartPool');
        if (ctx2) {
            if (chartPool) chartPool.destroy();
            chartPool = new Chart(ctx2, {
                type: 'bar',
                data: {
                    labels: d.labels,
                    datasets: [
                        {
                            label: '活跃活码',
                            data: d.activeQr,
                            backgroundColor: '#198754'
                        },
                        {
                            label: '满员活码',
                            data: d.fullQr,
                            backgroundColor: '#ffc107'
                        }
                    ]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: { position: 'top' },
                        tooltip: {
                            callbacks: {
                                label: function (ctx) {
                                    var total = d.activeQr[ctx.dataIndex] + d.fullQr[ctx.dataIndex];
                                    return ctx.dataset.label + ': ' + ctx.raw + '（总 ' + total + '）';
                                }
                            }
                        }
                    },
                    scales: {
                        x: { stacked: true },
                        y: { stacked: true, beginAtZero: true, title: { display: true, text: '活码数' } }
                    }
                }
            });
        }
    }

    // ── 漏斗 ─────────────────────────────────────

    function loadFunnels() {
        fetch('/dashboard/api/funnels')
            .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
            .then(renderFunnels)
            .catch(function (e) { logApiError('漏斗', e); });
    }

    function renderFunnels(d) {
        renderFunnel('funnelPool', d.poolFunnel.steps);
        renderFunnel('funnelQr', d.qrFunnel.steps);
    }

    function renderFunnel(containerId, steps) {
        var container = document.getElementById(containerId);
        if (!container || !steps || steps.length === 0) return;
        var maxVal = Math.max.apply(null, steps.map(function (s) { return s.value; }));
        if (maxVal === 0) maxVal = 1;
        var colors = ['#0d6efd', '#6f42c1', '#198754', '#fd7e14'];
        container.innerHTML = '';
        steps.forEach(function (step, i) {
            var pct = Math.max(step.value * 100 / maxVal, 15);
            var div = document.createElement('div');
            div.className = 'funnel-step';
            div.style.width = pct + '%';
            div.style.background = colors[i];
            var span = document.createElement('span');
            span.textContent = step.label;
            div.appendChild(span);
            var strong = document.createElement('strong');
            strong.textContent = step.value.toLocaleString();
            div.appendChild(strong);
            container.appendChild(div);
        });
    }

    // ── 排行榜 ───────────────────────────────────

    function loadLeaderboards(range) {
        fetch('/dashboard/api/leaderboards?range=' + range)
            .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
            .then(renderLeaderboards)
            .catch(function (e) { logApiError('排行榜', e); });
    }

    function renderLeaderboards(d) {
        renderTable('leaderboardEmployees', d.employees, ['#', '员工', '添加数'], function (row) {
            var tr = document.createElement('tr');
            var td1 = document.createElement('td');
            td1.className = 'text-center fw-bold';
            td1.textContent = row.rank;
            tr.appendChild(td1);
            var td2 = document.createElement('td');
            td2.textContent = row.name;
            if (row.rank <= 3) {
                var badge = document.createElement('span');
                badge.className = 'badge bg-warning text-dark';
                badge.textContent = 'Top' + row.rank;
                td2.appendChild(document.createTextNode(' '));
                td2.appendChild(badge);
            }
            tr.appendChild(td2);
            var td3 = document.createElement('td');
            td3.className = 'text-end fw-semibold';
            td3.textContent = row.count.toLocaleString();
            tr.appendChild(td3);
            return tr;
        });

        renderTable('leaderboardQr', d.qrCodes, ['#', '活码', '添加数'], function (row) {
            var tr = document.createElement('tr');
            var td1 = document.createElement('td');
            td1.className = 'text-center fw-bold';
            td1.textContent = row.rank;
            tr.appendChild(td1);
            var td2 = document.createElement('td');
            td2.textContent = row.schoolName;
            if (row.rank <= 3) {
                var badge = document.createElement('span');
                badge.className = 'badge bg-warning text-dark';
                badge.textContent = 'Top' + row.rank;
                td2.appendChild(document.createTextNode(' '));
                td2.appendChild(badge);
            }
            tr.appendChild(td2);
            var td3 = document.createElement('td');
            td3.className = 'text-end fw-semibold';
            td3.textContent = row.count.toLocaleString();
            tr.appendChild(td3);
            return tr;
        });
    }

    function renderTable(tbodyId, data, headers, rowFn) {
        var tbody = document.getElementById(tbodyId);
        if (!tbody) return;
        tbody.innerHTML = '';
        if (!data || data.length === 0) {
            var tr = document.createElement('tr');
            var td = document.createElement('td');
            td.colSpan = headers.length;
            td.className = 'text-center text-muted py-3';
            td.textContent = '暂无数据';
            tr.appendChild(td);
            tbody.appendChild(tr);
            return;
        }
        data.forEach(function (row) {
            tbody.appendChild(rowFn(row));
        });
    }

    // ── 辅助 ─────────────────────────────────────

    function highlightRangeBtn(range) {
        document.querySelectorAll('.btn-range').forEach(function (b) {
            if (b.getAttribute('data-range') === range) {
                b.classList.remove('btn-outline-primary');
                b.classList.add('btn-primary');
            } else {
                b.classList.remove('btn-primary');
                b.classList.add('btn-outline-primary');
            }
        });
    }

    function formatDate(d) {
        return d.getFullYear() + '-' +
               String(d.getMonth() + 1).padStart(2, '0') + '-' +
               String(d.getDate()).padStart(2, '0');
    }

    // ── 辅助 ─────────────────────────────────────

    function logApiError(component, err) {
        if (window.console && window.console.warn) {
            console.warn('[Dashboard] ' + component + ' API 失败: ' + err.message);
        }
    }

    // ── 暴露给自动刷新脚本 ──────────────────────

    window.dashboardRefreshAll = function () {
        if (typeof fetchStats === 'function') fetchStats(currentRange);
        loadTrends(currentRange === 'today' ? 7 : parseInt(currentRange.replace('days', '')));
        loadFunnels();
        loadLeaderboards(currentRange);
    };
})();
