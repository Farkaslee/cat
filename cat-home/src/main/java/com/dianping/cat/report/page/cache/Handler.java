package com.dianping.cat.report.page.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.servlet.ServletException;

import org.unidal.lookup.annotation.Inject;
import org.unidal.lookup.util.StringUtils;
import org.unidal.web.mvc.PageHandler;
import org.unidal.web.mvc.annotation.InboundActionMeta;
import org.unidal.web.mvc.annotation.OutboundActionMeta;
import org.unidal.web.mvc.annotation.PayloadMeta;

import com.dianping.cat.Constants;
import com.dianping.cat.consumer.event.EventAnalyzer;
import com.dianping.cat.consumer.event.model.entity.EventReport;
import com.dianping.cat.consumer.transaction.TransactionAnalyzer;
import com.dianping.cat.consumer.transaction.model.entity.TransactionReport;
import com.dianping.cat.report.ReportPage;
import com.dianping.cat.report.graph.PieChart;
import com.dianping.cat.report.graph.PieChart.Item;
import com.dianping.cat.report.page.PayloadNormalizer;
import com.dianping.cat.report.page.cache.CacheReport.CacheNameItem;
import com.dianping.cat.report.page.model.spi.ModelService;
import com.dianping.cat.report.page.transaction.AllMachineMerger;
import com.dianping.cat.report.page.transaction.AllNameMerger;
import com.dianping.cat.report.service.impl.EventReportService;
import com.dianping.cat.report.service.impl.TransactionReportService;
import com.dianping.cat.service.ModelRequest;
import com.dianping.cat.service.ModelResponse;

public class Handler implements PageHandler<Context> {

	@Inject(type = ModelService.class, value = EventAnalyzer.ID)
	private ModelService<EventReport> m_eventService;

	@Inject
	private JspViewer m_jspViewer;

	@Inject
	private TransactionReportService m_transactionReportService;
	
	@Inject
	private EventReportService m_eventReportService;

	@Inject
	private PayloadNormalizer m_normalizePayload;

	@Inject(type = ModelService.class, value = TransactionAnalyzer.ID)
	private ModelService<TransactionReport> m_transactionService;

	private CacheReport buildCacheReport(TransactionReport transactionReport, EventReport eventReport, Payload payload) {
		String type = payload.getType();
		String queryName = payload.getQueryName();
		String ip = payload.getIpAddress();
		String sortBy = payload.getSortBy();
		TransactionReportVistor vistor = new TransactionReportVistor();

		vistor.setType(type).setQueryName(queryName).setSortBy(sortBy).setCurrentIp(ip);
		vistor.setEventReport(eventReport);
		vistor.visitTransactionReport(transactionReport);
		return vistor.getCacheReport();
	}

	private String buildPieChart(CacheReport report) {
		PieChart chart = new PieChart();
		List<Item> items = new ArrayList<Item>();
		List<CacheNameItem> nameItems = report.getNameItems();

		for (CacheNameItem cacheItem : nameItems) {
			String name = cacheItem.getName().getId();

			if (name.endsWith(":get") || name.endsWith(":mGet")) {
				items.add(new Item().setTitle(name).setNumber(cacheItem.getName().getTotalCount()));
			}
		}
		chart.addItems(items);
		return chart.getJsonString();
	}

	private EventReport getHistoryEventReport(Payload payload) {
		String domain = payload.getDomain();
		Date start = payload.getHistoryStartDate();
		Date end = payload.getHistoryEndDate();

		return m_eventReportService.queryReport(domain, start, end);
	}

	private TransactionReport getHistoryTransactionReport(Payload payload) {
		String domain = payload.getDomain();
		Date start = payload.getHistoryStartDate();
		Date end = payload.getHistoryEndDate();

		return m_transactionReportService.queryReport(domain, start, end);
	}

	private EventReport getHourlyEventReport(Payload payload) {
		String domain = payload.getDomain();
		String ipAddress = payload.getIpAddress();
		String type = payload.getType();
		ModelRequest request = new ModelRequest(domain, payload.getDate()) //
		      .setProperty("ip", ipAddress);
		EventReport eventReport = null;

		if (StringUtils.isEmpty(type)) {
			ModelResponse<EventReport> response = m_eventService.invoke(request);

			eventReport = response.getModel();
		} else {
			request.setProperty("type", type);
			ModelResponse<EventReport> response = m_eventService.invoke(request);

			eventReport = response.getModel();
		}
		if (Constants.ALL.equalsIgnoreCase(ipAddress)) {
			com.dianping.cat.report.page.event.AllMachineMerger allEvent = new com.dianping.cat.report.page.event.AllMachineMerger();

			allEvent.visitEventReport(eventReport);
			eventReport = allEvent.getReport();
		}
		if (Constants.ALL.equalsIgnoreCase(type)) {
			com.dianping.cat.report.page.event.AllNameMerger allEvent = new com.dianping.cat.report.page.event.AllNameMerger();

			allEvent.visitEventReport(eventReport);
			eventReport = allEvent.getReport();
		}

		return eventReport;
	}

	private TransactionReport getHourlyTransactionReport(Payload payload) {
		String domain = payload.getDomain();
		String ipAddress = payload.getIpAddress();
		String type = payload.getType();
		ModelRequest request = new ModelRequest(domain, payload.getDate()) //
		      .setProperty("ip", ipAddress);
		TransactionReport transactionReport = null;

		if (StringUtils.isNotEmpty(type)) {
			request.setProperty("type", type);
		}
		
		ModelResponse<TransactionReport> response = m_transactionService.invoke(request);
		
		transactionReport = response.getModel();

		if (Constants.ALL.equalsIgnoreCase(ipAddress)) {
			AllMachineMerger all = new AllMachineMerger();

			all.visitTransactionReport(transactionReport);
			transactionReport = all.getReport();
		}
		if (Constants.ALL.equalsIgnoreCase(type)) {
			AllNameMerger all = new AllNameMerger();

			all.visitTransactionReport(transactionReport);
			transactionReport = all.getReport();
		}
		return transactionReport;
	}

	@Override
	@PayloadMeta(Payload.class)
	@InboundActionMeta(name = "cache")
	public void handleInbound(Context ctx) throws ServletException, IOException {
		// display only, no action here
	}

	@Override
	@OutboundActionMeta(name = "cache")
	public void handleOutbound(Context ctx) throws ServletException, IOException {
		Model model = new Model(ctx);
		Payload payload = ctx.getPayload();
		String type = payload.getType();
		TransactionReport transactionReport = null;
		EventReport eventReport = null;

		normalize(model, payload);
		switch (payload.getAction()) {
		case HOURLY_REPORT:
			transactionReport = getHourlyTransactionReport(payload);
			eventReport = getHourlyEventReport(payload);
			break;
		case HISTORY_REPORT:
			transactionReport = getHistoryTransactionReport(payload);
			eventReport = getHistoryEventReport(payload);
			break;
		}

		if (transactionReport != null && eventReport != null) {
			CacheReport cacheReport = buildCacheReport(transactionReport, eventReport, payload);

			model.setReport(cacheReport);
			if (!StringUtils.isEmpty(type)) {
				model.setPieChart(buildPieChart(model.getReport()));
			}
		}
		m_jspViewer.view(ctx, model);
	}

	private void normalize(Model model, Payload payload) {
		m_normalizePayload.normalize(model, payload);
		model.setPage(ReportPage.CACHE);
		model.setQueryName(payload.getQueryName());
	}
}
