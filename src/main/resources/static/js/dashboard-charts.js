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
        // Chart.js CDN 不可用时前端降级为隐藏图表
        if (!hasChartJs) {
            var c1 = document.getElementById('chartAddAlerts');
            var c2 = document.getElementById('chartPool');
            if (c1) c1.parentElement.innerHTML = '<div class="text-muted text-center py-5">图表库加载失败，请刷新页面重试</div>';
            if (c2) c2.parentElement.innerHTML = '<div class="text-muted text-center py-5">图表库加载失败，请刷新页面重试</div>';
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
        var html = '';
        var colors = ['#0d6efd', '#6f42c1', '#198754', '#fd7e14'];
        steps.forEach(function (step, i) {
            var pct = Math.max(step.value * 100 / maxVal, 15);
            html += '<div class="funnel-step" style="width:' + pct + '%;background:' + colors[i] + ';">' +
                    '<span>' + step.label + '</span>' +
                    '<strong>' + step.value.toLocaleString() + '</strong>' +
                    '</div>';
        });
        container.innerHTML = html;
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
            var badge = row.rank <= 3 ? ' <span class="badge bg-warning text-dark">Top' + row.rank + '</span>' : '';
            return '<tr>' +
                   '<td class="text-center fw-bold">' + row.rank + '</td>' +
                   '<td>' + row.name + badge + '</td>' +
                   '<td class="text-end fw-semibold">' + row.count.toLocaleString() + '</td>' +
                   '</tr>';
        });

        renderTable('leaderboardQr', d.qrCodes, ['#', '活码', '添加数'], function (row) {
            var badge = row.rank <= 3 ? ' <span class="badge bg-warning text-dark">Top' + row.rank + '</span>' : '';
            return '<tr>' +
                   '<td class="text-center fw-bold">' + row.rank + '</td>' +
                   '<td>' + row.schoolName + badge + '</td>' +
                   '<td class="text-end fw-semibold">' + row.count.toLocaleString() + '</td>' +
                   '</tr>';
        });
    }

    function renderTable(tbodyId, data, headers, rowFn) {
        var tbody = document.getElementById(tbodyId);
        if (!tbody) return;
        if (!data || data.length === 0) {
            tbody.innerHTML = '<tr><td colspan="' + headers.length + '" class="text-center text-muted py-3">暂无数据</td></tr>';
            return;
        }
        tbody.innerHTML = data.map(rowFn).join('');
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
