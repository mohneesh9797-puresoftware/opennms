package org.opennms.netmgt.enlinkd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.opennms.core.criteria.Alias;
import org.opennms.core.criteria.Criteria;
import org.opennms.core.criteria.Alias.JoinType;
import org.opennms.core.criteria.restrictions.EqRestriction;
import org.opennms.netmgt.dao.api.BridgeBridgeLinkDao;
import org.opennms.netmgt.dao.api.BridgeElementDao;
import org.opennms.netmgt.dao.api.BridgeMacLinkDao;
import org.opennms.netmgt.dao.api.BridgeStpLinkDao;
import org.opennms.netmgt.dao.api.IpNetToMediaDao;
import org.opennms.netmgt.dao.api.IsIsElementDao;
import org.opennms.netmgt.dao.api.IsIsLinkDao;
import org.opennms.netmgt.dao.api.LldpElementDao;
import org.opennms.netmgt.dao.api.LldpLinkDao;
import org.opennms.netmgt.dao.api.NodeDao;
import org.opennms.netmgt.dao.api.OspfElementDao;
import org.opennms.netmgt.dao.api.OspfLinkDao;
import org.opennms.netmgt.dao.support.UpsertTemplate;
import org.opennms.netmgt.model.BridgeBridgeLink;
import org.opennms.netmgt.model.BridgeElement;
import org.opennms.netmgt.model.BridgeMacLink;
import org.opennms.netmgt.model.BridgeStpLink;
import org.opennms.netmgt.model.IpNetToMedia;
import org.opennms.netmgt.model.IsIsElement;
import org.opennms.netmgt.model.IsIsLink;
import org.opennms.netmgt.model.LldpElement;
import org.opennms.netmgt.model.LldpLink;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsNode.NodeType;
import org.opennms.netmgt.model.OspfElement;
import org.opennms.netmgt.model.OspfLink;
import org.opennms.netmgt.model.PrimaryType;
import org.opennms.netmgt.model.topology.BridgeTopology;
import org.opennms.netmgt.model.topology.BridgeTopology.BridgeTopologyLink;
import org.opennms.netmgt.model.topology.LinkableSnmpNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
public class EnhancedLinkdServiceImpl implements EnhancedLinkdService {
		
//	private final static Logger LOG = LoggerFactory.getLogger(EnhancedLinkdServiceImpl.class);

    @Autowired
    private PlatformTransactionManager m_transactionManager;
	
	private NodeDao m_nodeDao;

	private LldpLinkDao m_lldpLinkDao;
	
	private LldpElementDao m_lldpElementDao;
	
	private OspfLinkDao m_ospfLinkDao;
	
	private OspfElementDao m_ospfElementDao;
	
	private IsIsLinkDao m_isisLinkDao;

	private IsIsElementDao m_isisElementDao;
	
	private IpNetToMediaDao m_ipNetToMediaDao;
	
	private BridgeElementDao m_bridgeElementDao;
	
	private BridgeMacLinkDao m_bridgeMacLinkDao;
	
	private BridgeBridgeLinkDao m_bridgeBridgeLinkDao;
	
	private BridgeStpLinkDao m_bridgeStpLinkDao; 
	
	volatile Map<Integer,Map<Integer,Set<String>>> m_bftMap = new HashMap<Integer, Map<Integer,Set<String>>>();
	
	private void addBridgeForwardingTableEntry(Integer nodeid,Integer bridgeport, String mac) {
		Map<Integer,Set<String>> bft = new HashMap<Integer, Set<String>>();
        if (m_bftMap.containsKey(nodeid))
        	bft = m_bftMap.get(nodeid);
        Set<String> macs = new HashSet<String>();
        if (bft.containsKey(bridgeport)) {
            macs = bft.get(bridgeport);
        }
        macs.add(mac);

        bft.put(bridgeport, macs);
        m_bftMap.put(nodeid, bft);
	}

    @Override
	public List<LinkableSnmpNode> getSnmpNodeList() {
		final List<LinkableSnmpNode> nodes = new ArrayList<LinkableSnmpNode>();
		
		final Criteria criteria = new Criteria(OnmsNode.class);
		criteria.setAliases(Arrays.asList(new Alias[] {
	            new Alias("ipInterfaces", "iface", JoinType.LEFT_JOIN)
	        }));    
        criteria.addRestriction(new EqRestriction("type", NodeType.ACTIVE));
        criteria.addRestriction(new EqRestriction("iface.isSnmpPrimary", PrimaryType.PRIMARY));
        for (final OnmsNode node : m_nodeDao.findMatching(criteria)) {
            nodes.add(new LinkableSnmpNode(node.getId(), node.getPrimaryInterface().getIpAddress(), node.getSysObjectId(),node.getSysName()));
        }
        return nodes;
	}

	@Override
	public LinkableSnmpNode getSnmpNode(final int nodeid) {
		final Criteria criteria = new Criteria(OnmsNode.class);
		criteria.setAliases(Arrays.asList(new Alias[] {
	            new Alias("ipInterfaces", "iface", JoinType.LEFT_JOIN)
	        }));    
        criteria.addRestriction(new EqRestriction("type", NodeType.ACTIVE));
        criteria.addRestriction(new EqRestriction("iface.isSnmpPrimary", PrimaryType.PRIMARY));
        criteria.addRestriction(new EqRestriction("id", nodeid));
        final List<OnmsNode> nodes = m_nodeDao.findMatching(criteria);

        if (nodes.size() > 0) {
        	final OnmsNode node = nodes.get(0);
			return new LinkableSnmpNode(node.getId(), node.getPrimaryInterface().getIpAddress(), node.getSysObjectId(),node.getSysName());
        } else {
        	return null;
        }
	}

	@Override
	public void delete(int nodeId) {
		Date now = new Date();
		reconcileLldp(nodeId, now);
		reconcileCdp(nodeId, now);
		reconcileOspf(nodeId, now);
		reconcileIpNetToMedia(nodeId, now);
		reconcileBridge(nodeId, now);
	}

	@Override
	public void reconcileLldp(int nodeId, Date now) {
		LldpElement element = m_lldpElementDao.findByNodeId(nodeId);
		if (element != null && element.getLldpNodeLastPollTime().getTime() < now.getTime()) {
			m_lldpElementDao.delete(element);
			m_lldpElementDao.flush();
		}
		m_lldpLinkDao.deleteByNodeIdOlderThen(nodeId, now);
		m_lldpLinkDao.flush();
	}

	@Override
	public void reconcileOspf(int nodeId, Date now) {
		OspfElement element = m_ospfElementDao.findByNodeId(nodeId);
		if (element != null && element.getOspfNodeLastPollTime().getTime() <now.getTime()) {
			m_ospfElementDao.delete(element);
			m_ospfElementDao.flush();
		}
		m_ospfLinkDao.deleteByNodeIdOlderThen(nodeId, now);
		m_ospfLinkDao.flush();
	}

	@Override
	public void reconcileIsis(int nodeId, Date now) {
		IsIsElement element = m_isisElementDao.findByNodeId(nodeId);
		if (element != null && element.getIsisNodeLastPollTime().getTime() < now.getTime()) {
			m_isisElementDao.delete(element);
			m_isisElementDao.flush();
		}
		m_isisLinkDao.deleteByNodeIdOlderThen(nodeId, now);
		m_isisLinkDao.flush();
	}

	@Override
	public void reconcileCdp(int nodeId, Date now) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void reconcileIpNetToMedia(int nodeId, Date now) {
		m_ipNetToMediaDao.deleteBySourceNodeIdOlderThen(nodeId, now);
		m_ipNetToMediaDao.flush();
	}

	@Override
	public void store(int nodeId, LldpLink link) {
		if (link == null)
			return;
		saveLldpLink(nodeId, link);
	}

	@Transactional
    protected void saveLldpLink(final int nodeId, final LldpLink saveMe) {
		new UpsertTemplate<LldpLink, LldpLinkDao>(m_transactionManager,m_lldpLinkDao) {

			@Override
			protected LldpLink query() {
				return m_dao.get(nodeId, saveMe.getLldpLocalPortNum());
			}

			@Override
			protected LldpLink doUpdate(LldpLink dbLldpLink) {
				dbLldpLink.merge(saveMe);
				m_dao.update(dbLldpLink);
				m_dao.flush();
				return dbLldpLink;
			}

			@Override
			protected LldpLink doInsert() {
				final OnmsNode node = m_nodeDao.get(nodeId);
				if ( node == null )
					return null;
				saveMe.setNode(node);
				saveMe.setLldpLinkLastPollTime(saveMe.getLldpLinkCreateTime());
				m_dao.saveOrUpdate(saveMe);
				m_dao.flush();
				return saveMe;
			}
			
		}.execute();
	}

	@Override
	@Transactional
	public void store(int nodeId, LldpElement element) {
		if (element ==  null)
			return;
		final OnmsNode node = m_nodeDao.get(nodeId);
		if ( node == null )
			return;
		
		LldpElement dbelement = node.getLldpElement();
		if (dbelement != null) {
			dbelement.merge(element);
			node.setLldpElement(dbelement);
		} else {
			element.setNode(node);
			element.setLldpNodeLastPollTime(element.getLldpNodeCreateTime());
			node.setLldpElement(element);
		}

        m_nodeDao.saveOrUpdate(node);
		m_nodeDao.flush();
		
	}

	@Override
	public void store(int nodeId, OspfLink link) {
		if (link == null)
			return;
		saveOspfLink(nodeId, link);
	}
	
	private void saveOspfLink(final int nodeId, final OspfLink saveMe) {
		new UpsertTemplate<OspfLink, OspfLinkDao>(m_transactionManager,m_ospfLinkDao) {

			@Override
			protected OspfLink query() {
				return m_dao.get(nodeId, saveMe.getOspfRemRouterId(),saveMe.getOspfRemIpAddr(),saveMe.getOspfRemAddressLessIndex());
			}

			@Override
			protected OspfLink doUpdate(OspfLink dbOspfLink) {
				dbOspfLink.merge(saveMe);
				m_dao.update(dbOspfLink);
				m_dao.flush();
				return dbOspfLink;
			}

			@Override
			protected OspfLink doInsert() {
				final OnmsNode node = m_nodeDao.get(nodeId);
				if ( node == null )
					return null;
				saveMe.setNode(node);
				saveMe.setOspfLinkLastPollTime(saveMe.getOspfLinkCreateTime());
				m_dao.saveOrUpdate(saveMe);
				m_dao.flush();
				return saveMe;
			}
			
		}.execute();
		
	}

	@Override
	public void store(int nodeId, IsIsLink link) {
		if (link == null)
			return;
		saveIsisLink(nodeId, link);
	}

	@Transactional
    protected void saveIsisLink(final int nodeId, final IsIsLink saveMe) {
		new UpsertTemplate<IsIsLink, IsIsLinkDao>(m_transactionManager,m_isisLinkDao) {

			@Override
			protected IsIsLink query() {
				return m_dao.get(nodeId, saveMe.getIsisCircIndex(),saveMe.getIsisISAdjIndex());
			}

			@Override
			protected IsIsLink doUpdate(IsIsLink dbIsIsLink) {
				dbIsIsLink.merge(saveMe);
				m_dao.update(dbIsIsLink);
				m_dao.flush();
				return dbIsIsLink;
			}

			@Override
			protected IsIsLink doInsert() {
				final OnmsNode node = m_nodeDao.get(nodeId);
				if ( node == null )
					return null;
				saveMe.setNode(node);
				saveMe.setIsisLinkLastPollTime(saveMe.getIsisLinkCreateTime());
				m_dao.saveOrUpdate(saveMe);
				m_dao.flush();
				return saveMe;
			}
			
		}.execute();
	}

	@Override
	@Transactional
	public void store(int nodeId, OspfElement element) {
		if (element ==  null)
			return;
		final OnmsNode node = m_nodeDao.get(nodeId);
		if ( node == null )
			return;
		
		OspfElement dbelement = node.getOspfElement();
		if (dbelement != null) {
			dbelement.merge(element);
			node.setOspfElement(dbelement);
		} else {
			element.setNode(node);
			element.setOspfNodeLastPollTime(element.getOspfNodeCreateTime());
			node.setOspfElement(element);
		}

        m_nodeDao.saveOrUpdate(node);
		m_nodeDao.flush();
		
	}

	@Override
	@Transactional
	public void store(int nodeId, IsIsElement element) {
		if (element ==  null)
			return;
		final OnmsNode node = m_nodeDao.get(nodeId);
		if ( node == null )
			return;
		
		IsIsElement dbelement = node.getIsisElement();
		if (dbelement != null) {
			dbelement.merge(element);
			node.setIsisElement(dbelement);
		} else {
			element.setNode(node);
			element.setIsisNodeLastPollTime(element.getIsisNodeCreateTime());
			node.setIsisElement(element);
		}

        m_nodeDao.saveOrUpdate(node);
		m_nodeDao.flush();
		
	}

	@Override
	public void store(int nodeId, BridgeElement bridge) {
		if (bridge == null)
			return;
		saveBridgeElement(nodeId, bridge);
	}

	@Transactional
    protected void saveBridgeElement(final int nodeId, final BridgeElement saveMe) {
		new UpsertTemplate<BridgeElement, BridgeElementDao>(m_transactionManager,m_bridgeElementDao) {

			@Override
			protected BridgeElement query() {
				return m_dao.getByNodeIdVlan(nodeId,saveMe.getVlan());
			}

			@Override
			protected BridgeElement doUpdate(BridgeElement bridge) {
				bridge.merge(saveMe);
				m_dao.update(bridge);
				m_dao.flush();
				return bridge;
			}

			@Override
			protected BridgeElement doInsert() {
				final OnmsNode node = m_nodeDao.get(nodeId);
				if ( node == null )
					return null;
				saveMe.setBridgeNodeLastPollTime(saveMe.getBridgeNodeCreateTime());
				m_dao.saveOrUpdate(saveMe);
				m_dao.flush();
				return saveMe;
			}
			
		}.execute();
	}

	@Override
	public void store(int nodeId, BridgeStpLink link) {
		if (link == null)
			return;
		saveBridgeStpLink(nodeId, link);
		
	}

	@Transactional
    protected void saveBridgeStpLink(final int nodeId, final BridgeStpLink saveMe) {
		new UpsertTemplate<BridgeStpLink, BridgeStpLinkDao>(m_transactionManager,m_bridgeStpLinkDao) {

			@Override
			protected BridgeStpLink query() {
				return m_dao.getByNodeIdBridgePort(nodeId,saveMe.getStpPort());
			}

			@Override
			protected BridgeStpLink doUpdate(BridgeStpLink link) {
				link.merge(saveMe);
				m_dao.update(link);
				m_dao.flush();
				return link;
			}

			@Override
			protected BridgeStpLink doInsert() {
				final OnmsNode node = m_nodeDao.get(nodeId);
				if ( node == null )
					return null;
				saveMe.setBridgeStpLinkLastPollTime(saveMe.getBridgeStpLinkCreateTime());
				m_dao.saveOrUpdate(saveMe);
				m_dao.flush();
				return saveMe;
			}
			
		}.execute();
	}

	@Override
	public void store(int nodeId, BridgeMacLink link) {
		if (link == null)
			return;
		addBridgeForwardingTableEntry(nodeId, link.getBridgePort(), link.getMacAddress());
		
	}

	@Override
	public synchronized void reconcileBridge(int nodeId, Date now) {
		m_bridgeElementDao.deleteByNodeIdOlderThen(nodeId, now);
		m_bridgeElementDao.flush();

		m_bridgeStpLinkDao.deleteByNodeIdOlderThen(nodeId, now);
		m_bridgeStpLinkDao.flush();
		
		Map<Integer,Set<String>> bft = m_bftMap.remove(nodeId);
		if (bft == null || bft.isEmpty())
			return;
		Set<String> macs = new HashSet<String>();
		for (Set<String> portmacs: bft.values()) 
			macs.addAll(portmacs);
		
		Map<Integer,Map<Integer,Set<String>>> savedtopology = new HashMap<Integer, Map<Integer,Set<String>>>();
		for (BridgeMacLink maclink: m_bridgeMacLinkDao.findAll()) {
			if (maclink.getNode().getId().intValue() == nodeId)
				continue;
			if (macs.contains(maclink.getMacAddress())) {
				Map<Integer,Set<String>> nodesavedtopology = new HashMap<Integer, Set<String>>();
				if (savedtopology.containsKey(maclink.getNode().getId())) 
					nodesavedtopology = savedtopology.get(maclink.getNode().getId());
				Set<String> macsonport = new HashSet<String>();
				if (nodesavedtopology.containsKey(maclink.getBridgePort()))
					macsonport = nodesavedtopology.get(maclink.getBridgePort());
				macsonport.add(maclink.getMacAddress());
				nodesavedtopology.put(maclink.getBridgePort(), macsonport);
				savedtopology.put(maclink.getNode().getId(), nodesavedtopology);
				break;	
			}
		}
		
		BridgeTopology topology = new BridgeTopology();
		for (Integer savednode: savedtopology.keySet()) {
			topology.addTopology(savednode, savedtopology.get(savednode),new HashSet<Integer>());
		}
		Set<Integer> targets = new HashSet<Integer>();
		targets.add(nodeId);
		for (BridgeBridgeLink bblink: m_bridgeBridgeLinkDao.findByNodeId(nodeId)) {
			Map<Integer,Set<String>> nodesavedtopology = new HashMap<Integer, Set<String>>();
			nodesavedtopology.put(bblink.getDesignatedPort(), new HashSet<String>());
			topology.addTopology(bblink.getDesignatedNode().getId(), nodesavedtopology, targets);
		}
		for (BridgeBridgeLink bblink: m_bridgeBridgeLinkDao.findByDesignatedNodeId(nodeId)) {
			Map<Integer,Set<String>> nodesavedtopology = new HashMap<Integer, Set<String>>();
			nodesavedtopology.put(bblink.getBridgePort(), new HashSet<String>());
			topology.addTopology(bblink.getNode().getId(), nodesavedtopology, targets);
		}
		topology.parseBFT(nodeId, bft);

		// now check the topology with the old one
		// delete the not found links
		for (BridgeTopologyLink btl: topology.getTopology()) {
				saveLink(btl);
		}
		for (Integer curNodeId: savedtopology.keySet()) {
			m_bridgeMacLinkDao.deleteByNodeIdOlderThen(curNodeId, now);
		}
		m_bridgeMacLinkDao.deleteByNodeIdOlderThen(nodeId, now);
		m_bridgeMacLinkDao.flush();

		// What about bridge bridge topology
		// The changes could only be regarding the nodeId
		m_bridgeBridgeLinkDao.deleteByNodeIdOlderThen(nodeId, now);
		m_bridgeBridgeLinkDao.deleteByDesignatedNodeIdOlderThen(nodeId, now);
		m_bridgeBridgeLinkDao.flush();

	}
	
	protected void saveLink(final BridgeTopologyLink bridgelink) {
		OnmsNode node = m_nodeDao.get(bridgelink.getBridgeTopologyPort().getNodeid());
		if (node == null)
			return;
		OnmsNode designatenode = null;
		if (bridgelink.getDesignateBridgePort() != null)
			designatenode = m_nodeDao.get(bridgelink.getDesignateBridgePort().getNodeid());
		if (bridgelink.getMacs().isEmpty() && designatenode != null) {
			BridgeBridgeLink link = new BridgeBridgeLink();
			link.setNode(node);
			link.setBridgePort(bridgelink.getBridgeTopologyPort().getBridgePort());
			link.setDesignatedNode(designatenode);
			link.setDesignatedPort(bridgelink.getDesignateBridgePort().getBridgePort());
			saveBridgeBridgeLink(link);
			return;
		} 
		for (String mac: bridgelink.getMacs()) {
			BridgeMacLink maclink1 = new BridgeMacLink();
			maclink1.setNode(node);
			maclink1.setBridgePort(bridgelink.getBridgeTopologyPort().getBridgePort());
			maclink1.setMacAddress(mac);
			saveBridgeMacLink(maclink1);
			if (designatenode == null)
				continue;
			BridgeMacLink maclink2 = new BridgeMacLink();
			maclink2.setNode(designatenode);
			maclink2.setBridgePort(bridgelink.getDesignateBridgePort().getBridgePort());
			maclink2.setMacAddress(mac);
			saveBridgeMacLink(maclink2);
			
		}
	}

	@Transactional
    protected void saveBridgeMacLink(final BridgeMacLink saveMe) {
		new UpsertTemplate<BridgeMacLink, BridgeMacLinkDao>(m_transactionManager,m_bridgeMacLinkDao) {

			@Override
			protected BridgeMacLink query() {
				return m_dao.getByNodeIdBridgePort(saveMe.getNode().getId(),saveMe.getBridgePort());
			}

			@Override
			protected BridgeMacLink doUpdate(BridgeMacLink link) {
				link.merge(saveMe);
				m_dao.update(link);
				m_dao.flush();
				return link;
			}

			@Override
			protected BridgeMacLink doInsert() {
				saveMe.setBridgeMacLinkLastPollTime(saveMe.getBridgeMacLinkCreateTime());
				m_dao.saveOrUpdate(saveMe);
				m_dao.flush();
				return saveMe;
			}
			
		}.execute();
	}

	
	@Transactional
    protected void saveBridgeBridgeLink(final BridgeBridgeLink saveMe) {
		new UpsertTemplate<BridgeBridgeLink, BridgeBridgeLinkDao>(m_transactionManager,m_bridgeBridgeLinkDao) {

			@Override
			protected BridgeBridgeLink query() {
				return m_dao.getByNodeIdBridgePort(saveMe.getNode().getId(),saveMe.getBridgePort());
			}

			@Override
			protected BridgeBridgeLink doUpdate(BridgeBridgeLink link) {
				link.merge(saveMe);
				m_dao.update(link);
				m_dao.flush();
				return link;
			}

			@Override
			protected BridgeBridgeLink doInsert() {
				saveMe.setBridgeBridgeLinkLastPollTime(saveMe.getBridgeBridgeLinkCreateTime());
				m_dao.saveOrUpdate(saveMe);
				m_dao.flush();
				return saveMe;
			}
			
		}.execute();
	}

	@Override
	public void store(int nodeId, IpNetToMedia ipnettomedia) {
		if (ipnettomedia == null)
			return;
		saveIpNetToMedia(nodeId, ipnettomedia);
	}

	@Transactional
    protected void saveIpNetToMedia(final int nodeId, final IpNetToMedia saveMe) {
		new UpsertTemplate<IpNetToMedia, IpNetToMediaDao>(m_transactionManager,m_ipNetToMediaDao) {

			@Override
			protected IpNetToMedia query() {
				return m_dao.getByNetAndPhysAddress(saveMe.getNetAddress(),saveMe.getPhysAddress());
			}

			@Override
			protected IpNetToMedia doUpdate(IpNetToMedia dbIpNetToMedia) {
				final OnmsNode node = m_nodeDao.get(nodeId);
				if (node == null)
					return null;
				saveMe.setSourceNode(node);
				dbIpNetToMedia.merge(saveMe);
				m_dao.update(dbIpNetToMedia);
				m_dao.flush();
				return dbIpNetToMedia;
			}

			@Override
			protected IpNetToMedia doInsert() {
				final OnmsNode node = m_nodeDao.get(nodeId);
				if ( node == null )
					return null;
				saveMe.setSourceNode(node);
				saveMe.setLastPollTime(saveMe.getCreateTime());
				m_dao.saveOrUpdate(saveMe);
				m_dao.flush();
				return saveMe;
			}
			
		}.execute();
	}

	
	public LldpLinkDao getLldpLinkDao() {
		return m_lldpLinkDao;
	}

	public void setLldpLinkDao(LldpLinkDao lldpLinkDao) {
		m_lldpLinkDao = lldpLinkDao;
	}

	public NodeDao getNodeDao() {
		return m_nodeDao;
	}

	public void setNodeDao(NodeDao nodeDao) {
		m_nodeDao = nodeDao;
	}

	public OspfLinkDao getOspfLinkDao() {
		return m_ospfLinkDao;
	}

	public void setOspfLinkDao(OspfLinkDao ospfLinkDao) {
		m_ospfLinkDao = ospfLinkDao;
	}

	public IsIsLinkDao getIsisLinkDao() {
		return m_isisLinkDao;
	}

	public void setIsisLinkDao(IsIsLinkDao isisLinkDao) {
		m_isisLinkDao = isisLinkDao;
	}

	public LldpElementDao getLldpElementDao() {
		return m_lldpElementDao;
	}

	public void setLldpElementDao(LldpElementDao lldpElementDao) {
		m_lldpElementDao = lldpElementDao;
	}

	public OspfElementDao getOspfElementDao() {
		return m_ospfElementDao;
	}

	public void setOspfElementDao(OspfElementDao ospfElementDao) {
		m_ospfElementDao = ospfElementDao;
	}

	public IsIsElementDao getIsisElementDao() {
		return m_isisElementDao;
	}

	public void setIsisElementDao(IsIsElementDao isisElementDao) {
		m_isisElementDao = isisElementDao;
	}

	public BridgeElementDao getBridgeElementDao() {
		return m_bridgeElementDao;
	}

	public void setBridgeElementDao(BridgeElementDao bridgeElementDao) {
		m_bridgeElementDao = bridgeElementDao;
	}

	public BridgeMacLinkDao getBridgeMacLinkDao() {
		return m_bridgeMacLinkDao;
	}

	public void setBridgeMacLinkDao(BridgeMacLinkDao bridgeMacLinkDao) {
		m_bridgeMacLinkDao = bridgeMacLinkDao;
	}

	public BridgeBridgeLinkDao getBridgeBridgeLinkDao() {
		return m_bridgeBridgeLinkDao;
	}

	public void setBridgeBridgeLinkDao(BridgeBridgeLinkDao bridgeBridgeLinkDao) {
		m_bridgeBridgeLinkDao = bridgeBridgeLinkDao;
	}

	public BridgeStpLinkDao getBridgeStpLinkDao() {
		return m_bridgeStpLinkDao;
	}

	public void setBridgeStpLinkDao(BridgeStpLinkDao bridgeStpLinkDao) {
		m_bridgeStpLinkDao = bridgeStpLinkDao;
	}

	public IpNetToMediaDao getIpNetToMediaDao() {
		return m_ipNetToMediaDao;
	}

	public void setIpNetToMediaDao(IpNetToMediaDao ipNetToMediaDao) {
		m_ipNetToMediaDao = ipNetToMediaDao;
	}
}
