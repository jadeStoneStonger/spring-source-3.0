/*
 * Copyright 2002-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.xml;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.SystemPropertyUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Default implementation of the {@link BeanDefinitionDocumentReader} interface.
 * Reads bean definitions according to the "spring-beans" DTD and XSD format
 * (Spring's default XML bean definition format).
 *
 * <p>The structure, elements and attribute names of the required XML document
 * are hard-coded in this class. (Of course a transform could be run if necessary
 * to produce this format). <code>&lt;beans&gt;</code> doesn't need to be the root
 * element of the XML document: This class will parse all bean definition elements
 * in the XML file, not regarding the actual root element.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Erik Wiersma
 * @since 18.12.2003
 */
public class DefaultBeanDefinitionDocumentReader implements BeanDefinitionDocumentReader {

	public static final String BEAN_ELEMENT = BeanDefinitionParserDelegate.BEAN_ELEMENT;

	public static final String ALIAS_ELEMENT = "alias";

	public static final String NAME_ATTRIBUTE = "name";

	public static final String ALIAS_ATTRIBUTE = "alias";

	public static final String IMPORT_ELEMENT = "import";

	public static final String RESOURCE_ATTRIBUTE = "resource";


	protected final Log logger = LogFactory.getLog(getClass());

	private XmlReaderContext readerContext;


	/**
	 * Parses bean definitions according to the "spring-beans" DTD.
	 * <p>Opens a DOM Document; then initializes the default settings
	 * specified at <code>&lt;beans&gt;</code> level; then parses
	 * the contained bean definitions.
	 */
	public void registerBeanDefinitions(Document doc, XmlReaderContext readerContext) {
		this.readerContext = readerContext;

		logger.debug("Loading bean definitions");

		//提取root
		Element root = doc.getDocumentElement();

		BeanDefinitionParserDelegate delegate = createHelper(readerContext, root);

		//解析前处理，留给子类实现
		preProcessXml(root);

		//XML读取
		parseBeanDefinitions(root, delegate);

		//解析后处理，留给子类实现
		postProcessXml(root);
	}

	protected BeanDefinitionParserDelegate createHelper(XmlReaderContext readerContext, Element root) {
		BeanDefinitionParserDelegate delegate = new BeanDefinitionParserDelegate(readerContext);
		delegate.initDefaults(root);
		return delegate;
	}

	/**
	 * Return the descriptor for the XML resource that this parser works on.
	 */
	protected final XmlReaderContext getReaderContext() {
		return this.readerContext;
	}

	/**
	 * Invoke the {@link org.springframework.beans.factory.parsing.SourceExtractor} to pull the
	 * source metadata from the supplied {@link Element}.
	 */
	protected Object extractSource(Element ele) {
		return this.readerContext.extractSource(ele);
	}


    /**
     * Parse the elements at the root level in the document:
     * "import", "alias", "bean".
     * <p>
     * 解析文档中根级别的元素：“import”，“alias”，“bean”。
     *
     * @param root the DOM root element of the document
     */
    protected void parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) {
        if (delegate.isDefaultNamespace(delegate.getNamespaceURI(root))) {
            //默认命名空间解析

            //获取root节点下的子节点
            NodeList nl = root.getChildNodes();

            for (int i = 0; i < nl.getLength(); i++) {
                Node node = nl.item(i);
                if (node instanceof Element) {
                    Element ele = (Element) node;

                    String namespaceUri = delegate.getNamespaceURI(ele);

                    if (delegate.isDefaultNamespace(namespaceUri)) {
                        //解析默认标签
                        parseDefaultElement(ele, delegate);
                    } else {
                        delegate.parseCustomElement(ele);
                    }
                }
            }
        } else {
            //自定义命名空间解析
            delegate.parseCustomElement(root);
        }
    }

    /**
     * 解析默认标签
     *
     * @param ele
     * @param delegate
     */
	private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
        if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) {

            //解析“import”元素并将bean定义从给定资源加载到bean工厂中。
            importBeanDefinitionResource(ele);

        } else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) {

            //处理给定的别名元素，向注册表注册别名。
            processAliasRegistration(ele);

        } else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) {

            //处理给定的bean元素，解析bean定义并将其注册到注册表。
            processBeanDefinition(ele, delegate);
        }
    }

	/**
	 * Parse an "import" element and load the bean definitions
	 * from the given resource into the bean factory.
     *
     * 解析“import”元素并将bean定义从给定资源加载到bean工厂中。
	 */
	protected void importBeanDefinitionResource(Element ele) {
		String location = ele.getAttribute(RESOURCE_ATTRIBUTE);
		if (!StringUtils.hasText(location)) {
			getReaderContext().error("Resource location must not be empty", ele);
			return;
		}

		// Resolve system properties: e.g. "${user.dir}"
		location = SystemPropertyUtils.resolvePlaceholders(location);

		Set<Resource> actualResources = new LinkedHashSet<Resource>(4);

		// Discover whether the location is an absolute or relative URI
		boolean absoluteLocation = false;
		try {
			absoluteLocation = ResourcePatternUtils.isUrl(location) || ResourceUtils.toURI(location).isAbsolute();
		}
		catch (URISyntaxException ex) {
			// cannot convert to an URI, considering the location relative
			// unless it is the well-known Spring prefix "classpath*:"
		}

		// Absolute or relative?
		if (absoluteLocation) {
			try {
				int importCount = getReaderContext().getReader().loadBeanDefinitions(location, actualResources);
				if (logger.isDebugEnabled()) {
					logger.debug("Imported " + importCount + " bean definitions from URL location [" + location + "]");
				}
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error(
						"Failed to import bean definitions from URL location [" + location + "]", ele, ex);
			}
		}
		else {
			// No URL -> considering resource location as relative to the current file.
			try {
				int importCount;
				Resource relativeResource = getReaderContext().getResource().createRelative(location);
				if (relativeResource.exists()) {
					importCount = getReaderContext().getReader().loadBeanDefinitions(relativeResource);
					actualResources.add(relativeResource);
				}
				else {
					String baseLocation = getReaderContext().getResource().getURL().toString();
					importCount = getReaderContext().getReader().loadBeanDefinitions(
							StringUtils.applyRelativePath(baseLocation, location), actualResources);
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Imported " + importCount + " bean definitions from relative location [" + location + "]");
				}
			}
			catch (IOException ex) {
				getReaderContext().error("Failed to resolve current resource location", ele, ex);
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error("Failed to import bean definitions from relative location [" + location + "]",
						ele, ex);
			}
		}
		Resource[] actResArray = actualResources.toArray(new Resource[actualResources.size()]);
		getReaderContext().fireImportProcessed(location, actResArray, extractSource(ele));
	}

	/**
	 * Process the given alias element, registering the alias with the registry.
     * 处理给定的别名元素，向注册表注册别名。
	 */
	protected void processAliasRegistration(Element ele) {
        String name = ele.getAttribute(NAME_ATTRIBUTE);
        String alias = ele.getAttribute(ALIAS_ATTRIBUTE);
        boolean valid = true;
        if (!StringUtils.hasText(name)) {
            getReaderContext().error("Name must not be empty", ele);
            valid = false;
        }
        if (!StringUtils.hasText(alias)) {
            getReaderContext().error("Alias must not be empty", ele);
            valid = false;
        }
        if (valid) {
            try {
                getReaderContext().getRegistry().registerAlias(name, alias);
            } catch (Exception ex) {
                getReaderContext().error("Failed to register alias '" + alias +
                        "' for bean with name '" + name + "'", ele, ex);
            }
            getReaderContext().fireAliasRegistered(name, alias, extractSource(ele));
        }
    }

	/**
	 * Process the given bean element, parsing the bean definition
	 * and registering it with the registry.
     *
     * 处理给定的bean元素，解析bean定义并将其注册到注册表。
	 */
	protected void processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) {

        //解析提供的<bean>元素。 如果在解析期间出现错误，则可能返回null
        BeanDefinitionHolder bdHolder = delegate.parseBeanDefinitionElement(ele);
        if (bdHolder != null) {

            bdHolder = delegate.decorateBeanDefinitionIfRequired(ele, bdHolder);

            try {
                // Register the final decorated instance.
                BeanDefinitionReaderUtils.registerBeanDefinition(bdHolder, getReaderContext().getRegistry());
            } catch (BeanDefinitionStoreException ex) {
                getReaderContext().error("Failed to register bean definition with name '" +
                        bdHolder.getBeanName() + "'", ele, ex);
            }
            // Send registration event.
            getReaderContext().fireComponentRegistered(new BeanComponentDefinition(bdHolder));
        }
    }


	/**
	 * Allow the XML to be extensible by processing any custom element types first,
	 * before we start to process the bean definitions. This method is a natural
	 * extension point for any other custom pre-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * @see #getReaderContext()
	 */
	protected void preProcessXml(Element root) {
	}

	/**
	 * Allow the XML to be extensible by processing any custom element types last,
	 * after we finished processing the bean definitions. This method is a natural
	 * extension point for any other custom post-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * @see #getReaderContext()
	 */
	protected void postProcessXml(Element root) {
	}

}
