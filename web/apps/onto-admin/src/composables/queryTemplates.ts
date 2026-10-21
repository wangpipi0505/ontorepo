/** 模板只描述本候选的模型元数据；用户可编辑，服务端执行受限 SELECT 校验。 */
export const queryTemplates = [
  {
    label: '列出对象',
    code: 'list_classes',
    name: '列出领域对象',
    query: `PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?class ?name WHERE {
  ?class a owl:Class ; rdfs:label ?name .
}
ORDER BY ?name`,
  },
  {
    label: '列出属性',
    code: 'list_properties',
    name: '列出对象属性',
    query: `PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?property ?name ?domain ?range WHERE {
  ?property a owl:DatatypeProperty ; rdfs:label ?name ;
    rdfs:domain ?domain ; rdfs:range ?range .
}
ORDER BY ?name`,
  },
]
